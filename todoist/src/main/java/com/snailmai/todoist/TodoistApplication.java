package com.snailmai.todoist;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 独立用户进程；不提升原公开工具服务的文件权限。 */
@SpringBootApplication
public class TodoistApplication {
    public static void main(String[] args) {
        if(args.length>0 && args[0].equals("--check-service")) {
            try { DeploymentCheck.run(args); }
            catch(Failure failure) { System.err.println("Check failed: " + failure.code); System.exit(1); }
            catch(Exception ignored) { System.err.println("Check failed: SERVICE_UNAVAILABLE"); System.exit(1); }
            return;
        }
        SpringApplication.run(TodoistApplication.class, args);
    }
}
