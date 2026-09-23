pipeline {
    agent { label 'linux && arm64' }

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
        stage('Test and build') {
            steps {
                sh 'bash ./mvnw -B clean verify'
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'target/surefire-reports/**', allowEmptyArchive: true
        }
        success {
            archiveArtifacts artifacts: 'target/myBlog.war', fingerprint: true
        }
        cleanup {
            deleteDir()
        }
    }
}
