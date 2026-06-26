package com.enterprise.ai.agent.platform.control.auth;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlPlatformAuthProviderTest {

    @Test
    void authenticatesBase64SamlResponseAndMapsAttributes() {
        PlatformAuthProperties properties = properties("https://idp.example.com", "reachai-sp");
        SamlPlatformAuthProvider provider = new SamlPlatformAuthProvider(properties);

        PlatformUserProfile profile = provider.authenticate(PlatformLoginRequest.builder()
                .samlResponse(base64(saml("https://idp.example.com", "reachai-sp",
                        Instant.now().minusSeconds(30), Instant.now().plusSeconds(300))))
                .build());

        assertEquals("SAML", profile.sourceProvider());
        assertEquals("iam-user-1", profile.externalSubject());
        assertEquals("zhangsan", profile.username());
        assertEquals("张三", profile.displayName());
        assertEquals("zhangsan@example.com", profile.email());
        assertTrue(profile.roleCodes().contains("PLATFORM_ADMIN"));
        assertTrue(profile.roleCodes().contains("AUDITOR"));
    }

    @Test
    void rejectsMismatchedAudience() {
        PlatformAuthProperties properties = properties("https://idp.example.com", "reachai-sp");
        SamlPlatformAuthProvider provider = new SamlPlatformAuthProvider(properties);

        PlatformLoginRequest request = PlatformLoginRequest.builder()
                .samlResponse(base64(saml("https://idp.example.com", "other-sp",
                        Instant.now().minusSeconds(30), Instant.now().plusSeconds(300))))
                .build();

        assertThrows(IllegalArgumentException.class, () -> provider.authenticate(request));
    }

    @Test
    void rejectsUnsignedSamlResponseWhenTrustedSigningKeyIsConfigured() throws Exception {
        KeyPair keyPair = keyPair();
        PlatformAuthProperties properties = properties("https://idp.example.com", "reachai-sp");
        properties.getSaml().setRequireSignedResponse(true);
        properties.getSaml().setTrustedPublicKeyPem(publicKeyPem(keyPair.getPublic()));
        SamlPlatformAuthProvider provider = new SamlPlatformAuthProvider(properties);

        PlatformLoginRequest request = PlatformLoginRequest.builder()
                .samlResponse(base64(saml("https://idp.example.com", "reachai-sp",
                        Instant.now().minusSeconds(30), Instant.now().plusSeconds(300))))
                .build();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> provider.authenticate(request));
        assertTrue(ex.getMessage().contains("signature"));
    }

    @Test
    void authenticatesSignedSamlResponseWithTrustedPublicKey() throws Exception {
        KeyPair keyPair = keyPair();
        PlatformAuthProperties properties = properties("https://idp.example.com", "reachai-sp");
        properties.getSaml().setRequireSignedResponse(true);
        properties.getSaml().setTrustedPublicKeyPem(publicKeyPem(keyPair.getPublic()));
        SamlPlatformAuthProvider provider = new SamlPlatformAuthProvider(properties);

        String signedXml = signXml(saml("https://idp.example.com", "reachai-sp",
                Instant.now().minusSeconds(30), Instant.now().plusSeconds(300)), keyPair.getPrivate());
        PlatformUserProfile profile = provider.authenticate(PlatformLoginRequest.builder()
                .samlResponse(base64(signedXml))
                .build());

        assertEquals("iam-user-1", profile.externalSubject());
    }

    @Test
    void runtimeProviderConfigCanEnableAndConfigureSaml() {
        PlatformAuthProperties properties = new PlatformAuthProperties();
        SamlPlatformAuthProvider provider = new SamlPlatformAuthProvider(properties);

        PlatformUserProfile profile = provider.authenticate(PlatformLoginRequest.builder()
                .providerConfig(Map.of(
                        "enabled", true,
                        "issuer", "https://runtime-idp.example.com",
                        "entityId", "runtime-sp",
                        "usernameAttribute", "login",
                        "displayNameAttribute", "name",
                        "rolesAttribute", "groups",
                        "rolesDelimiter", "|",
                        "clockSkewSeconds", 120))
                .samlResponse(base64(runtimeSaml("https://runtime-idp.example.com", "runtime-sp",
                        Instant.now().minusSeconds(30), Instant.now().plusSeconds(300))))
                .build());

        assertEquals("wangwu", profile.username());
        assertEquals("Wang Wu", profile.displayName());
        assertEquals(java.util.Set.of("PROJECT_OWNER", "AUDITOR"), profile.roleCodes());
    }

    private PlatformAuthProperties properties(String issuer, String entityId) {
        PlatformAuthProperties properties = new PlatformAuthProperties();
        properties.getSaml().setEnabled(true);
        properties.getSaml().setIssuer(issuer);
        properties.getSaml().setEntityId(entityId);
        return properties;
    }

    private String base64(String xml) {
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }

    private String saml(String issuer, String audience, Instant notBefore, Instant notOnOrAfter) {
        return """
                <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
                    xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">
                  <saml:Issuer>%s</saml:Issuer>
                  <samlp:Status>
                    <samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/>
                  </samlp:Status>
                  <saml:Assertion>
                    <saml:Issuer>%s</saml:Issuer>
                    <saml:Subject>
                      <saml:NameID>iam-user-1</saml:NameID>
                    </saml:Subject>
                    <saml:Conditions NotBefore="%s" NotOnOrAfter="%s">
                      <saml:AudienceRestriction>
                        <saml:Audience>%s</saml:Audience>
                      </saml:AudienceRestriction>
                    </saml:Conditions>
                    <saml:AttributeStatement>
                      <saml:Attribute Name="username"><saml:AttributeValue>zhangsan</saml:AttributeValue></saml:Attribute>
                      <saml:Attribute Name="displayName"><saml:AttributeValue>张三</saml:AttributeValue></saml:Attribute>
                      <saml:Attribute Name="email"><saml:AttributeValue>zhangsan@example.com</saml:AttributeValue></saml:Attribute>
                      <saml:Attribute Name="roles"><saml:AttributeValue>PLATFORM_ADMIN,AUDITOR</saml:AttributeValue></saml:Attribute>
                    </saml:AttributeStatement>
                  </saml:Assertion>
                </samlp:Response>
                """.formatted(issuer, issuer, notBefore, notOnOrAfter, audience);
    }

    private String runtimeSaml(String issuer, String audience, Instant notBefore, Instant notOnOrAfter) {
        return """
                <samlp:Response xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
                    xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion">
                  <saml:Issuer>%s</saml:Issuer>
                  <samlp:Status>
                    <samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/>
                  </samlp:Status>
                  <saml:Assertion>
                    <saml:Issuer>%s</saml:Issuer>
                    <saml:Subject>
                      <saml:NameID>iam-user-2</saml:NameID>
                    </saml:Subject>
                    <saml:Conditions NotBefore="%s" NotOnOrAfter="%s">
                      <saml:AudienceRestriction>
                        <saml:Audience>%s</saml:Audience>
                      </saml:AudienceRestriction>
                    </saml:Conditions>
                    <saml:AttributeStatement>
                      <saml:Attribute Name="login"><saml:AttributeValue>wangwu</saml:AttributeValue></saml:Attribute>
                      <saml:Attribute Name="name"><saml:AttributeValue>Wang Wu</saml:AttributeValue></saml:Attribute>
                      <saml:Attribute Name="groups"><saml:AttributeValue>PROJECT_OWNER|AUDITOR</saml:AttributeValue></saml:Attribute>
                    </saml:AttributeStatement>
                  </saml:Assertion>
                </samlp:Response>
                """.formatted(issuer, issuer, notBefore, notOnOrAfter, audience);
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String publicKeyPem(PublicKey publicKey) {
        return """
                -----BEGIN PUBLIC KEY-----
                %s
                -----END PUBLIC KEY-----
                """.formatted(Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(publicKey.getEncoded()));
    }

    private String signXml(String xml, PrivateKey privateKey) throws Exception {
        DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();
        documentFactory.setNamespaceAware(true);
        Document document = documentFactory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        XMLSignatureFactory signatureFactory = XMLSignatureFactory.getInstance("DOM");
        Reference reference = signatureFactory.newReference(
                "",
                signatureFactory.newDigestMethod(DigestMethod.SHA256, null),
                List.of(signatureFactory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
                null,
                null);
        SignedInfo signedInfo = signatureFactory.newSignedInfo(
                signatureFactory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, (C14NMethodParameterSpec) null),
                signatureFactory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                List.of(reference));
        DOMSignContext signContext = new DOMSignContext(privateKey, document.getDocumentElement());
        signatureFactory.newXMLSignature(signedInfo, null).sign(signContext);

        StringWriter writer = new StringWriter();
        var transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }
}
