// 手工触发的后端 CI：节点需提供 Java 21，使用仓库 Maven Wrapper，不操作线上服务。
pipeline {
    agent { label 'linux && arm64' }

    // 限制记录保留与运行时长，并避免同一任务的工作区并发写入。
    options {
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
        disableConcurrentBuilds()
        timeout(time: 20, unit: 'MINUTES')
    }

    stages {
        stage('Environment') {
            steps {
                sh 'java -version'
            }
        }
        // clean verify 同时执行测试和 WAR 打包；任一步失败均阻止成功产物归档。
        stage('Test and build') {
            steps {
                sh 'bash ./mvnw -B clean verify'
            }
        }
    }

    post {
        // 失败时也保留测试报告；测试必须只使用合成数据，不能放入真实密钥或密码。
        always {
            archiveArtifacts artifacts: 'target/surefire-reports/**', allowEmptyArchive: true
        }
        success {
            archiveArtifacts artifacts: 'target/myBlog.war', fingerprint: true
        }
        // 仅清理 Jenkins 工作区，归档产物和 Mac mini 已部署文件不在此目录。
        cleanup {
            deleteDir()
        }
    }
}
