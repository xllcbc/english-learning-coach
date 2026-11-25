package com.writelab.conversation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class TeacherConversationConfig {

    @Bean(name = "teacherConversationExecutor", destroyMethod = "shutdown")
    ExecutorService teacherConversationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
