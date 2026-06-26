package com.enterprise.ai.agent.platform.control.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class SamlPlatformAuthProvider implements PlatformAuthProvider {

    private final PlatformAuthProperties properties;

    @Override
    public String providerType() {
        return "SAML";
    }

    @Override
    public PlatformUserProfile authenticate(PlatformLoginRequest request) {
        PlatformAuthProperties.Saml saml = resolveConfig(request);
        if (!saml.isEnabled()) {
            throw new IllegalArgumentException("SAML provider is disabled");
        }
        if (!StringUtils.hasText(request.getSamlResponse())) {
            throw new IllegalArgumentException("SAML response is required");
        }
        Document document = parseDocument(request.getSamlResponse());
        requireSuccessStatus(document);
        Element assertion = firstElement(document, "Assertion");
        if (assertion == null) {
            throw new IllegalArgumentException("SAML assertion is missing");
        }
        validateTrustedSignature(document, assertion, saml);
        validateIssuer(assertion, document, saml);
        validateConditions(assertion, saml);

        String subject = firstText(assertion, "NameID");
        if (!StringUtils.hasText(subject)) {
            throw new IllegalArgumentException("SAML subject NameID is required");
        }
        String username = firstNonBlank(
                attribute(assertion, saml.getUsernameAttribute()),
                subject);
        String displayName = firstNonBlank(attribute(assertion, saml.getDisplayNameAttribute()), username);
        String email = attribute(assertion, saml.getEmailAttribute());
        String mobile = attribute(assertion, saml.getMobileAttribute());
        Set<String> roles = roles(attributeValues(assertion, saml.getRolesAttribute()), saml);
        return new PlatformUserProfile("SAML", subject, username, displayName, email, mobile, roles);
    }

    private PlatformAuthProperties.Saml resolveConfig(PlatformLoginRequest request) {
        MapReader config = new MapReader(request == null ? null : request.getProviderConfig());
        PlatformAuthProperties.Saml base = properties.getSaml();
        PlatformAuthProperties.Saml saml = new PlatformAuthProperties.Saml();
        saml.setEnabled(config.bool("enabled", base.isEnabled()));
        saml.setEntityId(config.text("entityId", base.getEntityId()));
        saml.setMetadataUri(config.text("metadataUri", base.getMetadataUri()));
        saml.setIssuer(config.text("issuer", base.getIssuer()));
        saml.setUsernameAttribute(config.text("usernameAttribute", base.getUsernameAttribute()));
        saml.setDisplayNameAttribute(config.text("displayNameAttribute", base.getDisplayNameAttribute()));
        saml.setEmailAttribute(config.text("emailAttribute", base.getEmailAttribute()));
        saml.setMobileAttribute(config.text("mobileAttribute", base.getMobileAttribute()));
        saml.setRolesAttribute(config.text("rolesAttribute", base.getRolesAttribute()));
        saml.setRolesDelimiter(config.text("rolesDelimiter", base.getRolesDelimiter()));
        saml.setClockSkewSeconds(config.longValue("clockSkewSeconds", base.getClockSkewSeconds()));
        saml.setRequireSignedResponse(config.bool("requireSignedResponse", base.isRequireSignedResponse()));
        saml.setTrustedCertificatePem(config.text("trustedCertificatePem", base.getTrustedCertificatePem()));
        saml.setTrustedPublicKeyPem(config.text("trustedPublicKeyPem", base.getTrustedPublicKeyPem()));
        return saml;
    }

    private Document parseDocument(String samlResponse) {
        try {
            String payload = samlResponse.trim();
            byte[] xml = payload.startsWith("<")
                    ? payload.getBytes(StandardCharsets.UTF_8)
                    : Base64.getMimeDecoder().decode(payload);
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception ex) {
            throw new IllegalArgumentException("SAML response is not valid XML", ex);
        }
    }

    private void requireSuccessStatus(Document document) {
        Element statusCode = firstElement(document, "StatusCode");
        if (statusCode == null) {
            return;
        }
        String value = statusCode.getAttribute("Value");
        if (StringUtils.hasText(value) && !value.toUpperCase(Locale.ROOT).endsWith(":SUCCESS")
                && !"SUCCESS".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("SAML response status is not success");
        }
    }

    private void validateIssuer(Element assertion, Document document, PlatformAuthProperties.Saml saml) {
        String expectedIssuer = saml.getIssuer();
        if (!StringUtils.hasText(expectedIssuer)) {
            return;
        }
        String issuer = firstText(assertion, "Issuer");
        if (!StringUtils.hasText(issuer)) {
            issuer = firstText(document.getDocumentElement(), "Issuer");
        }
        if (!expectedIssuer.trim().equals(issuer == null ? null : issuer.trim())) {
            throw new IllegalArgumentException("SAML issuer does not match");
        }
    }

    private void validateConditions(Element assertion, PlatformAuthProperties.Saml saml) {
        Element conditions = firstElement(assertion, "Conditions");
        if (conditions == null) {
            return;
        }
        Instant now = Instant.now();
        long skew = Math.max(0, saml.getClockSkewSeconds());
        String notBefore = conditions.getAttribute("NotBefore");
        if (StringUtils.hasText(notBefore) && now.plusSeconds(skew).isBefore(Instant.parse(notBefore))) {
            throw new IllegalArgumentException("SAML assertion is not active yet");
        }
        String notOnOrAfter = conditions.getAttribute("NotOnOrAfter");
        if (StringUtils.hasText(notOnOrAfter) && !now.minusSeconds(skew).isBefore(Instant.parse(notOnOrAfter))) {
            throw new IllegalArgumentException("SAML assertion has expired");
        }
        String expectedAudience = saml.getEntityId();
        if (StringUtils.hasText(expectedAudience)) {
            boolean matched = elements(conditions, "Audience").stream()
                    .map(Element::getTextContent)
                    .filter(StringUtils::hasText)
                    .anyMatch(value -> expectedAudience.trim().equals(value.trim()));
            if (!matched) {
                throw new IllegalArgumentException("SAML audience does not match");
            }
        }
    }

    private void validateTrustedSignature(Document document, Element assertion, PlatformAuthProperties.Saml saml) {
        if (!requiresTrustedSignature(saml)) {
            return;
        }
        PublicKey trustedKey = trustedSigningKey(saml);
        markIdAttributes(document.getDocumentElement());
        NodeList signatures = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature");
        XMLSignatureFactory signatureFactory = XMLSignatureFactory.getInstance("DOM");
        for (int i = 0; i < signatures.getLength(); i++) {
            Node node = signatures.item(i);
            if (!(node instanceof Element signatureElement) || !isTrustedSignatureLocation(signatureElement, document, assertion)) {
                continue;
            }
            try {
                DOMValidateContext context = new DOMValidateContext(trustedKey, signatureElement);
                context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
                XMLSignature signature = signatureFactory.unmarshalXMLSignature(context);
                if (signature.validate(context)) {
                    return;
                }
            } catch (Exception ex) {
                throw new IllegalArgumentException("SAML response signature is invalid", ex);
            }
        }
        throw new IllegalArgumentException("SAML response signature is required or invalid");
    }

    private boolean requiresTrustedSignature(PlatformAuthProperties.Saml saml) {
        return saml.isRequireSignedResponse()
                || StringUtils.hasText(saml.getTrustedPublicKeyPem())
                || StringUtils.hasText(saml.getTrustedCertificatePem());
    }

    private PublicKey trustedSigningKey(PlatformAuthProperties.Saml saml) {
        try {
            if (StringUtils.hasText(saml.getTrustedCertificatePem())) {
                byte[] der = pemBytes(saml.getTrustedCertificatePem(), "CERTIFICATE");
                CertificateFactory factory = CertificateFactory.getInstance("X.509");
                X509Certificate certificate = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
                return certificate.getPublicKey();
            }
            if (StringUtils.hasText(saml.getTrustedPublicKeyPem())) {
                byte[] der = pemBytes(saml.getTrustedPublicKeyPem(), "PUBLIC KEY");
                return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            }
        } catch (Exception ex) {
            throw new IllegalArgumentException("SAML trusted signing key is invalid", ex);
        }
        throw new IllegalArgumentException("SAML trusted signing key is required");
    }

    private byte[] pemBytes(String pem, String label) {
        String normalized = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(normalized);
    }

    private boolean isTrustedSignatureLocation(Element signatureElement, Document document, Element assertion) {
        Node parent = signatureElement.getParentNode();
        return parent == document.getDocumentElement() || parent == assertion;
    }

    private void markIdAttributes(Element element) {
        for (String attribute : List.of("ID", "Id", "id")) {
            if (element.hasAttribute(attribute)) {
                element.setIdAttribute(attribute, true);
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                markIdAttributes(childElement);
            }
        }
    }

    private String attribute(Element assertion, String name) {
        return attributeValues(assertion, name).stream().findFirst().orElse(null);
    }

    private List<String> attributeValues(Element assertion, String name) {
        if (!StringUtils.hasText(name)) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Element attribute : elements(assertion, "Attribute")) {
            String attributeName = attribute.getAttribute("Name");
            String friendlyName = attribute.getAttribute("FriendlyName");
            if (!name.equals(attributeName) && !name.equals(friendlyName)) {
                continue;
            }
            elements(attribute, "AttributeValue").stream()
                    .map(Element::getTextContent)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(values::add);
        }
        return values;
    }

    private Set<String> roles(List<String> values, PlatformAuthProperties.Saml saml) {
        String delimiter = StringUtils.hasText(saml.getRolesDelimiter())
                ? saml.getRolesDelimiter()
                : ",";
        return values.stream()
                .flatMap(value -> Stream.of(value.split(java.util.regex.Pattern.quote(delimiter))))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String firstText(Element root, String localName) {
        Element element = firstElement(root, localName);
        return element == null ? null : element.getTextContent();
    }

    private Element firstElement(Document document, String localName) {
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            nodes = document.getElementsByTagName(localName);
        }
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private Element firstElement(Element root, String localName) {
        List<Element> matches = elements(root, localName);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private List<Element> elements(Element root, String localName) {
        NodeList nodes = root.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) {
            nodes = root.getElementsByTagName(localName);
        }
        List<Element> elements = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    private record MapReader(Map<String, Object> values) {
        private String text(String key, String fallback) {
            Object value = values == null ? null : values.get(key);
            return value == null || !StringUtils.hasText(String.valueOf(value)) ? fallback : String.valueOf(value);
        }

        private boolean bool(String key, boolean fallback) {
            Object value = values == null ? null : values.get(key);
            return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
        }

        private long longValue(String key, long fallback) {
            Object value = values == null ? null : values.get(key);
            if (value == null || !StringUtils.hasText(String.valueOf(value))) {
                return fallback;
            }
            return Long.parseLong(String.valueOf(value));
        }
    }
}
