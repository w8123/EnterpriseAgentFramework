package com.enterprise.ai.control.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PlatformPasswordConfiguration {

    @Bean
    public PasswordEncoder platformPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
