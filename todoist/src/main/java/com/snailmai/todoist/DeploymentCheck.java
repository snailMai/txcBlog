package com.snailmai.todoist;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** 部署检查不发送模型轮次，也不读取或校验真实待办。 */
final class DeploymentCheck {
    static void run(String[] args) throws Exception {
        Failure.require(args.length == 2 && Set.of("http://127.0.0.1:8091", "http://127.0.0.1:8789", "https://tools.20242233.xyz").contains(args[1]), "INVALID_REQUEST");
        String base = args[1];
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        List<String> paths = base.endsWith(":8091") ? List.of("/todoist/health")
            : List.of("/", "/Todoist", "/ToolsCryption", "/GetRandomPWD", "/api/tools/get/randompwd?num=16&quantity=1");
        for (String path : paths) {
            var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8)).build();
            Failure.require(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200, "SERVICE_CHECK_FAILED");
        }
        System.out.println("HTTP checks passed; no conversation turn or task file access performed");
    }
}
