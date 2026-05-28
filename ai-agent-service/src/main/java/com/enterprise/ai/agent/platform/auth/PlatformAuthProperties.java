package com.enterprise.ai.agent.platform.auth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "eaf.auth")
public class PlatformAuthProperties {

    private String provider = "LOCAL";

    private Local local = new Local();

    private Header header = new Header();

    private Oidc oidc = new Oidc();

    private Saml saml = new Saml();

    private Session session = new Session();

    @Data
    public static class Local {
        private boolean enabled = true;
        private BootstrapAdmin bootstrapAdmin = new BootstrapAdmin();
    }

    @Data
    public static class BootstrapAdmin {
        private String username = "admin";
        private String password = "admin123";
        private String displayName = "Platform Admin";
    }

    @Data
    public static class Header {
        private boolean enabled = false;
        private String usernameHeader = "X-EAF-User";
        private String subjectHeader = "X-EAF-Subject";
        private String displayNameHeader = "X-EAF-Display-Name";
        private String emailHeader = "X-EAF-Email";
        private String mobileHeader = "X-EAF-Mobile";
        private String rolesHeader = "X-EAF-Roles";
        private String rolesDelimiter = ",";
    }

    @Data
    public static class Oidc {
        private boolean enabled = false;
        private String issuerUri;
        private String jwkSetUri;
        private String clientId;
        private String usernameClaim = "preferred_username";
        private String rolesClaim = "roles";
    }

    @Data
    public static class Saml {
        private boolean enabled = false;
        private String entityId;
        private String metadataUri;
        private String issuer;
        private String usernameAttribute = "username";
        private String displayNameAttribute = "displayName";
        private String emailAttribute = "email";
        private String mobileAttribute = "mobile";
        private String rolesAttribute = "roles";
        private String rolesDelimiter = ",";
        private long clockSkewSeconds = 60;
        private boolean requireSignedResponse = false;
        private String trustedCertificatePem;
        private String trustedPublicKeyPem;
    }

    @Data
    public static class Session {
        private long ttlSeconds = 7200;
    }
}
