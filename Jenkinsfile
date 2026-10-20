pipeline {
    agent any

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '15'))
        timeout(time: 60, unit: 'MINUTES')
    }

    parameters {
        booleanParam(name: 'DEPLOY', defaultValue: false, description: '构建成功后部署到本机；首次需先配置 HTTPS 反向代理')
    }

    environment {
        DEPLOY_ROOT = '/opt/rsdp-office'
        ENV_FILE = '/opt/rsdp-office/.env'
        COMPOSE_PARALLEL_LIMIT = '1'
    }

    stages {
        stage('Checkout') {
            steps { checkout scm }
        }
        stage('Preflight') {
            steps {
                sh 'bash scripts/deploy.sh check'
            }
        }
        stage('Java tests') {
            steps {
                sh '''#!/usr/bin/env bash
set -euo pipefail
test_image="rsdp-office-tests:${BUILD_NUMBER}"
rm -rf test-results/java
docker build --target test -t "$test_image" backend
container=$(docker create "$test_image")
trap 'docker rm "$container" >/dev/null' EXIT
mkdir -p test-results/java
docker cp "$container:/build/target/surefire-reports/." test-results/java/
'''
            }
            post {
                always {
                    junit testResults: 'test-results/java/TEST-*.xml', allowEmptyResults: true
                }
            }
        }
        stage('Build images') {
            steps { sh 'bash scripts/deploy.sh build' }
        }
        stage('Deploy') {
            when { expression { params.DEPLOY } }
            steps {
                // This job is configured to check out main. Refuse other commits.
                sh '''#!/usr/bin/env bash
set -euo pipefail
test "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" || {
    echo '只允许部署当前 origin/main。' >&2
    exit 1
}
bash scripts/deploy.sh deploy
'''
            }
        }
    }
    post {
        success { echo '构建通过；部署结果请查看 Deploy 阶段。配置文件保留在服务器，不进入镜像或构建归档。' }
        failure { echo '流水线失败。查看失败阶段；部署备份位于 /opt/rsdp-office/backups。' }
    }
}
