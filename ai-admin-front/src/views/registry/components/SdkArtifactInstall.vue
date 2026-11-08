<script setup lang="ts">
import { computed } from 'vue'
import { Download } from '@element-plus/icons-vue'
import CodeSnippetBlock from '@/components/common/CodeSnippetBlock.vue'
import { LiquidGlassNotice } from '@/components/common/liquid-glass'
import type { SdkArtifact } from '@/types/scanProject'
import { highlightXmlCode } from '../composables/sdkAccessWizardCodeHighlight'
import { useSdkAccessWizardActions } from '../composables/useSdkAccessWizardActions'
import type { SdkAccessLanguage } from '../composables/useSdkAccessWizardUiState'

const props = defineProps<{
  language: SdkAccessLanguage
  artifacts: SdkArtifact[]
  packageUrl?: string
}>()
const { copyText } = useSdkAccessWizardActions()

const packageInstructions = {
  csharp: '将核验后的 NuGet 包放入持久的本地 feed，配置业务项目使用该 feed，并替换安装命令中的项目路径。',
  python: '在业务项目的 Python 环境中执行安装命令，将命令中的 wheel 路径替换为下载并核验后的实际路径。',
  node: '在业务项目中执行安装命令，将命令中的 npm 包路径替换为下载并核验后的实际路径。',
  go: '将核验后的 ZIP 解压，把其中的 reachai-go 目录放到业务项目的 third_party/ 下，再执行安装命令。',
}
const sha256 = /^[a-f0-9]{64}$/i
const selectedArtifacts = computed(() => props.artifacts.filter((item) => item.language === props.language))
const javaArtifacts = computed(() => ['reachai-capability-sdk', 'reachai-spring-boot2-starter'].map(
  (id) => selectedArtifacts.value.find((artifact) => artifact.artifactId === id),
).filter((artifact): artifact is SdkArtifact => Boolean(artifact)))
const javaReady = computed(() => Boolean(props.packageUrl) && javaArtifacts.value.length === 2
  && javaArtifacts.value.every((item) => item.groupId && item.version && item.downloadUrl && item.pomDownloadUrl
    && sha256.test(item.integritySha256 || '') && sha256.test(item.pomIntegritySha256 || '')
    && item.installCommandTemplate))
const packageArtifact = computed(() => selectedArtifacts.value.find((item) => item.downloadUrl
  && sha256.test(item.integritySha256 || '') && item.installCommandTemplate))
const javaInstall = computed(() => javaArtifacts.value.map((item) => item.installCommandTemplate).join('\n\n'))
const javaPom = computed(() => javaArtifacts.value.map((item) => `<!-- ${item.artifactId === 'reachai-capability-sdk' ? '声明注解的业务模块' : 'Spring Boot 启动模块'} -->
<dependency>
  <groupId>${item.groupId}</groupId>
  <artifactId>${item.artifactId}</artifactId>
  <version>${item.version}</version>
</dependency>`).join('\n\n'))
</script>

<template>
  <div class="sdk-install-guide">
    <template v-if="language === 'java' && javaReady">
      <div class="sdk-install-group">
        <el-link :href="packageUrl" :icon="Download" type="primary" download>下载接入工具包</el-link>
        <p>解压后把 <code>{skillExtractDir}</code> 替换为解压目录。按顺序安装 Capability SDK 和 Starter，脚本会校验 JAR 和 POM 的摘要。</p>
        <CodeSnippetBlock variant="guide" title="PowerShell · 安装 Java SDK" :code="javaInstall" @copy="copyText" />
      </div>
      <div class="sdk-install-group">
        <p>将以下依赖放入对应 Maven 模块。多模块项目在声明注解的模块引入 Capability SDK，在启动模块引入 Starter。</p>
        <CodeSnippetBlock variant="guide" title="pom.xml" :code="javaPom" :highlighted-code="highlightXmlCode(javaPom)" @copy="copyText" />
      </div>
    </template>
    <template v-else-if="language !== 'java' && packageArtifact">
      <div class="sdk-install-group">
        <el-link :href="packageArtifact.downloadUrl || undefined" :icon="Download" type="primary" download>
          下载 {{ packageArtifact.packageName }} {{ packageArtifact.version }}
        </el-link>
        <p>下载后核对文件的 SHA-256，再执行安装命令。</p>
        <CodeSnippetBlock variant="guide" title="SHA-256" :code="packageArtifact.integritySha256 || ''" @copy="copyText" />
      </div>
      <div class="sdk-install-group">
        <p>{{ packageInstructions[language] }}</p>
        <CodeSnippetBlock variant="guide" title="安装命令" :code="packageArtifact.installCommandTemplate || ''" @copy="copyText" />
      </div>
    </template>
    <LiquidGlassNotice v-else type="warning" source="backdrop" title="当前技术栈的 SDK 制品或摘要暂不可用"
      description="请让平台管理员检查当前版本的分发制品，再重新加载安装说明。" />
  </div>
</template>

<style scoped lang="scss">
.sdk-install-guide,
.sdk-install-group {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
}

.sdk-install-guide {
  gap: var(--layout-panel-padding);
}

.sdk-install-group {
  gap: var(--layout-control-gap);

  p {
    max-inline-size: 48rem;
    margin: 0;
    color: var(--text-secondary);
    font-size: 15px;
    line-height: 1.75;
  }

  :deep(.el-link) {
    justify-self: start;
    min-width: 0;
    max-width: 100%;
    color: var(--brand-on-surface);
    overflow-wrap: anywhere;
  }
}
</style>
