package com.enterprise.ai.pipeline.document.job;

import com.enterprise.ai.domain.entity.DocumentImportJob;
import com.enterprise.ai.pipeline.document.*;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactException;
import com.enterprise.ai.pipeline.document.artifact.DocumentArtifactStore;
import com.enterprise.ai.repository.DocumentImportJobRepository;
import com.enterprise.ai.service.PipelineImportService;
import com.enterprise.ai.support.ArtifactLifecycleTestSupport;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the actual Worker in owned JVMs; repository and remote artifact IO are explicit substitutes. */
class DocumentParseTemporaryFileTest {
    private static final String PREFIX="reachai-document-parse-", FOREIGN=PREFIX+"foreign.json";
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings={"serialize-halt","upload-halt","serialize-kill"})
    void abruptWorkerExitLeavesNoParsedTemporaryFile(String mode) throws Exception { run(mode); }

    @ParameterizedTest @ValueSource(strings={"success","serialization-error","upload-error","large-stream"})
    void ordinaryCompletionAndFailuresReleaseTheOwnedTemporaryFile(String mode) throws Exception { run(mode); }

    private void run(String mode) throws Exception {
        Path root=directory.resolve(mode),spool=root.resolve("spool");Files.createDirectories(spool);
        Files.writeString(spool.resolve(FOREIGN),"不得删除其他任务的文件",StandardCharsets.UTF_8);
        Path log=root.resolve("child.log");
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var child=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx96m",
                "-Dfile.encoding=UTF-8","-Djava.io.tmpdir="+spool,"-cp",classpath,
                DocumentParseTemporaryFileTest.class.getName(),root.toString(),mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if(mode.equals("serialize-kill")) {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                while(!Files.exists(root.resolve("ready"))&&child.isAlive()&&System.nanoTime()<deadline)Thread.sleep(25);
                assertTrue(Files.exists(root.resolve("ready")),Files.readString(log));
                child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS));
                assertNotEquals(0,child.exitValue());
            } else {
                assertTrue(child.waitFor(45,TimeUnit.SECONDS),"Owned Worker probe must terminate");
                assertEquals(mode.equals("serialize-halt")?31:mode.equals("upload-halt")?32:0,child.exitValue(),Files.readString(log));
            }
            assertTrue(Files.readString(log).contains("PARSE_TEMP_PROBE_"+mode),Files.readString(log));
            if(mode.equals("large-stream"))Files.readAllLines(log).stream().filter(line->line.startsWith("PARSE_TEMP_STREAMED ")).forEach(System.out::println);
            // Observe release without deleting anything; Windows may still expose an entry briefly after process exit.
            long cleanupStarted=System.nanoTime(),cleanupDeadline=cleanupStarted+TimeUnit.SECONDS.toNanos(5);
            var leftovers=leftovers(spool);
            if(!leftovers.isEmpty())System.out.println("PARSE_TEMP_POST_EXIT_OBSERVATION mode="+mode+" entries="+leftovers.size());
            while(!leftovers.isEmpty()&&System.nanoTime()<cleanupDeadline){Thread.sleep(25);leftovers=leftovers(spool);}
            assertTrue(leftovers.isEmpty(),"A stopped Worker must not leave parsed content: "+leftovers);
            System.out.println("PARSE_TEMP_RELEASE_OBSERVED mode="+mode+" elapsedMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-cleanupStarted));
            assertEquals("不得删除其他任务的文件",Files.readString(spool.resolve(FOREIGN),StandardCharsets.UTF_8));
            System.out.println("PARSE_TEMP_JVM_VERIFIED mode="+mode+" exit="+child.exitValue()+" ownedFilesRemaining=0 foreignFilePreserved=true");
        } finally {
            if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS));}
        }
    }

    private static java.util.List<String> leftovers(Path spool) throws IOException {
        try(var files=Files.list(spool)) {
            return files.filter(file->file.getFileName().toString().startsWith(PREFIX)
                    &&!file.getFileName().toString().equals(FOREIGN)).map(file->file.getFileName().toString()).toList();
        }
    }

    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Owned root and probe mode required");
        Path root=Path.of(args[0]);String mode=args[1];
        var mapper=new ObjectMapper();boolean defaultClose=mapper.getFactory().isEnabled(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        var module=new SimpleModule();
        module.addSerializer(DocumentParseResult.class,new JsonSerializer<DocumentParseResult>() {
            @Override public void serialize(DocumentParseResult value,JsonGenerator generator,SerializerProvider provider) throws IOException {
                generator.writeStartObject();generator.writeStringField("normalizedText","中文解析正文与来源");
                if(mode.startsWith("serialize-")||mode.equals("serialization-error")) {
                    generator.writeStringField("partial","序列化中的正文".repeat(2000));generator.flush();
                    mark(root,mode);
                    if(mode.equals("serialize-halt"))Runtime.getRuntime().halt(31);
                    if(mode.equals("serialize-kill")) {
                        Files.writeString(root.resolve("ready"),"ready");
                        try { new java.util.concurrent.CountDownLatch(1).await(); }
                        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}
                    }
                    throw new IOException("Controlled serialization failure");
                }
                if(mode.equals("large-stream")) {
                    generator.writeArrayFieldStart("blocks");String block="x".repeat(65536);
                    for(int i=0;i<2048;i++)generator.writeString(block);
                    generator.writeEndArray();
                }
                generator.writeEndObject();
            }
        });mapper.registerModule(module);
        DocumentImportJob job=new DocumentImportJob();job.setJobId("dij_temporary_probe");job.setFileId("file");
        job.setStatus("QUEUED");job.setStage("QUEUED");job.setAttemptCount(0);job.setMaxAttempts(3);job.setFileSize(4L);
        job.setFileName("中文文件.txt");job.setContentType("text/plain");job.setAutoCommit(0);job.setSourceObjectKey("source");
        var jobs=mock(DocumentImportJobRepository.class);when(jobs.selectOne(any())).thenReturn(job);
        when(jobs.claimForParsing(anyString(),anyString(),anyInt())).thenAnswer(call->{job.setStatus("PARSING");job.setLeaseOwner(call.getArgument(1));job.setAttemptCount(1);return 1;});
        when(jobs.lockValidParsing(anyString(),anyString())).thenReturn(job);
        when(jobs.finalizeParsing(anyString(),anyString(),anyString(),anyString(),anyString(),any())).thenAnswer(call->{job.setStatus("PARSED");return 1;});
        when(jobs.failParsing(anyString(),anyString(),anyString(),any(),anyString(),anyString())).thenAnswer(call->{job.setStatus(call.getArgument(2));return 1;});
        var uploaded=new AtomicBoolean();var retained=new AtomicBoolean();
        var store=new DocumentArtifactStore() {
            @Override public void put(String key,InputStream content,long length,String type) {
                uploaded.set(true);assertTrue(length>0);assertEquals("application/json",type);
                try {
                    if(mode.equals("upload-halt")||mode.equals("upload-error")) {
                        assertTrue(content.readNBytes(16).length>0);mark(root,mode);
                        if(mode.equals("upload-halt"))Runtime.getRuntime().halt(32);
                        throw new DocumentArtifactException("Controlled upload failure");
                    }
                    if(mode.equals("large-stream")) {
                        assertTrue(length>Runtime.getRuntime().maxMemory(),"Artifact must be larger than the child heap");
                        assertEquals(length,content.transferTo(OutputStream.nullOutputStream()));
                        System.out.println("PARSE_TEMP_STREAMED bytes="+length+" heap="+Runtime.getRuntime().maxMemory());
                    } else {
                        byte[] bytes=content.readAllBytes();assertEquals(length,bytes.length);
                        assertEquals("中文解析正文与来源",new ObjectMapper().readTree(bytes).get("normalizedText").asText());
                    }
                }catch(IOException e){throw new DocumentArtifactException("Probe read failed",e);}
            }
            @Override public InputStream open(String key){return new ByteArrayInputStream(new byte[4]);}
            @Override public void retain(String key){retained.set(true);}
            @Override public void retire(String key){}
        };
        var router=mock(DocumentParseRouter.class);when(router.parse(any())).thenReturn(DocumentParseResult.builder()
                .providerType(DocumentProviderType.JAVA_FAST).providerVersion("fixture").format(DocumentFormat.TXT).normalizedText("正文").build());
        var worker=new DocumentImportJobWorker(jobs,store,router,mock(PipelineImportService.class),mock(com.enterprise.ai.vector.VectorService.class),
                mapper,new DocumentImportJobProperties(),ArtifactLifecycleTestSupport.transactions());
        worker.process(job.getJobId());
        assertEquals(defaultClose,mapper.getFactory().isEnabled(JsonGenerator.Feature.AUTO_CLOSE_TARGET),"Shared mapper configuration must remain unchanged");
        if(mode.equals("serialization-error")){assertEquals("FAILED",job.getStatus());assertFalse(uploaded.get());assertFalse(retained.get());}
        else if(mode.equals("upload-error")){assertEquals("RETRY_WAIT",job.getStatus());assertTrue(uploaded.get());assertFalse(retained.get());}
        else {assertEquals("PARSED",job.getStatus());assertTrue(uploaded.get());assertTrue(retained.get());}
        mark(root,mode);
    }
    private static void mark(Path root,String mode) {
        System.out.println("PARSE_TEMP_PROBE_"+mode);System.out.flush();
    }
}
