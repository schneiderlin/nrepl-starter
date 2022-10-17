package repl.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import repl.R;

@Configuration
@EnableConfigurationProperties(StarterServiceProperties.class)
public class StarterAutoConfigure {

    @Autowired
    private StarterServiceProperties properties;

    @Autowired
    private Environment env;

    @Autowired
    private ApplicationContext applicationContext;

    @Bean(initMethod = "init")
    public R starterService() {
        return new R(properties, applicationContext, env);
    }


}