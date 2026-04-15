package ${basePackage};

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@ComponentScan(basePackages = "${basePackage}")
public class SkillAutoConfiguration {
}
