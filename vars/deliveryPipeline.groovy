import io.finacplus.cicd.DeployConfig
import io.finacplus.cicd.Deployer

/**
 * Entry point for the reusable delivery pipeline.
 *
 * An application repository consumes the entire pipeline with:
 *
 *     @Library('finacplus-cicd') _
 *     deliveryPipeline()
 *
 * All behaviour is derived from that repository's deploy-config.yaml, so
 * onboarding a new service — or a new target cluster — requires no change here.
 */
def call(Map opts = [:]) {
    String configPath = opts.configPath ?: 'deploy-config.yaml'

    pipeline {
        agent any

        options {
            timestamps()
            timeout(time: 30, unit: 'MINUTES')
            buildDiscarder(logRotator(numToKeepStr: '20'))
            disableConcurrentBuilds()
        }

        environment {
            CFG_PATH = "${configPath}"
        }

        stages {

            stage('Checkout') {
                steps {
                    script {
                        checkout scm
                        env.GIT_SHA   = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                        env.SHORT_SHA = env.GIT_SHA.take(7)
                        // Jenkins checks out a detached HEAD, so `rev-parse --abbrev-ref HEAD`
                        // returns the literal "HEAD". Resolve the real branch from the remote
                        // ref that points at this commit; fall back to GIT_BRANCH (set by the
                        // git plugin, e.g. "origin/main") and finally to BRANCH_NAME (multibranch).
                        String resolved = sh(returnStdout: true, script: """
                            git for-each-ref --format='%(refname:short)' --points-at HEAD refs/remotes/origin \
                              | head -1 | sed 's|^origin/||'
                        """).trim()
                        if (!resolved) {
                            resolved = (env.GIT_BRANCH ?: '').replaceFirst(/^origin\//, '')
                        }
                        if (!resolved || resolved == 'HEAD') {
                            resolved = env.BRANCH_NAME ?: 'unknown'
                        }
                        env.BRANCH = resolved
                        echo "commit=${env.SHORT_SHA} branch=${env.BRANCH} build=#${env.BUILD_NUMBER}"
                    }
                }
            }

            stage('Load config') {
                steps {
                    script {
                        cfg = DeployConfig.load(this, env.CFG_PATH)
                        env.IMAGE = cfg.imageRef(env.BRANCH, env.BUILD_NUMBER, env.SHORT_SHA)
                        List<Map> targets = cfg.environmentsForBranch(env.BRANCH)
                        echo "app=${cfg.appName} v${cfg.appVersion}"
                        echo "image=${env.IMAGE}"
                        echo "targets for branch '${env.BRANCH}': " +
                             (targets ? targets.collect { "${it.name}→${it.cluster}" }.join(', ')
                                      : 'none (build + test only)')
                    }
                }
            }

            stage('Test') {
                when { expression { cfg.testCommands } }
                steps {
                    script {
                        // Tests run in a throwaway container so the controller
                        // needs no language runtimes installed.
                        // Docker-outside-of-Docker: `docker run -v <path>` resolves the path
                        // on the HOST daemon, not inside this container — so a plain
                        // -v "$(pwd)" would mount an empty directory. `--volumes-from
                        // $(hostname)` inherits the controller's own volumes at identical
                        // paths, so the workspace is visible at the same location.
                        cfg.testCommands.each { String cmd ->
                            sh label: "test: ${cmd}", script: """
                                docker run --rm --volumes-from \$(hostname) -w "\$(pwd)" \
                                  python:3.12-slim \
                                  sh -c 'pip install -q -r requirements.txt pytest && ${cmd}'
                            """
                        }
                    }
                }
            }

            stage('Build image') {
                steps {
                    script {
                        sh label: "docker build", script: """
                            docker build \
                              --build-arg BUILD_NUMBER='${env.BUILD_NUMBER}' \
                              --build-arg GIT_COMMIT='${env.GIT_SHA}' \
                              --build-arg GIT_BRANCH='${env.BRANCH}' \
                              --build-arg APP_VERSION='${cfg.appVersion}' \
                              --build-arg BUILD_TIME="\$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
                              -f '${cfg.dockerfile}' -t '${env.IMAGE}' '${cfg.buildContext}'
                        """
                    }
                }
            }

            stage('Scan image') {
                when { expression { cfg.trivyEnabled } }
                steps {
                    script {
                        String unfixed = cfg.trivyIgnoreUnfixed ? '--ignore-unfixed' : ''
                        int rc = sh(returnStatus: true, label: 'trivy', script: """
                            trivy image --scanners vuln --severity '${cfg.trivySeverity}' \
                              ${unfixed} --no-progress --exit-code 1 '${env.IMAGE}'
                        """)
                        if (rc != 0) {
                            if (cfg.trivyBlocking) {
                                error("Trivy found ${cfg.trivySeverity} vulnerabilities — failing build (security.trivy.failOnFindings=true)")
                            }
                            unstable("Trivy found ${cfg.trivySeverity} vulnerabilities — reported, not blocking")
                        } else {
                            echo "Trivy: no ${cfg.trivySeverity} vulnerabilities"
                        }
                    }
                }
            }

            stage('Push image') {
                when { expression { cfg.environmentsForBranch(env.BRANCH) } }
                steps {
                    sh label: 'docker push', script: "docker push '${env.IMAGE}'"
                    echo "Pushed immutable artifact: ${env.IMAGE}"
                }
            }

            stage('Deploy') {
                when { expression { cfg.environmentsForBranch(env.BRANCH) } }
                steps {
                    script {
                        // Environments are promoted in the order declared in config.
                        cfg.environmentsForBranch(env.BRANCH).each { Map envSpec ->
                            stage("Deploy → ${envSpec.name}") {
                                if (!envSpec.autoDeploy) {
                                    Map ap = (envSpec.approval ?: [:]) as Map
                                    if (ap.required) {
                                        timeout(time: (ap.timeoutMinutes ?: 15) as int, unit: 'MINUTES') {
                                            input message: "Promote ${env.IMAGE} to ${envSpec.name} (${envSpec.cluster})?",
                                                  ok: "Deploy to ${envSpec.name}"
                                        }
                                    }
                                }

                                Deployer d = new Deployer(this, cfg, envSpec)
                                boolean ok = d.deploy(image: env.IMAGE,
                                                      commit: env.GIT_SHA,
                                                      buildNumber: env.BUILD_NUMBER)
                                if (!ok) {
                                    error("Deployment to ${envSpec.name} failed and was rolled back")
                                }
                                if (!d.smokeTest()) {
                                    d.rollback()
                                    error("Smoke test failed in ${envSpec.name} — rolled back")
                                }
                                echo "${envSpec.name} verified: ${env.IMAGE}"
                            }
                        }
                    }
                }
            }
        }

        post {
            success  { echo "SUCCESS — ${env.IMAGE ?: 'build'} delivered" }
            unstable { echo "UNSTABLE — delivered with reported findings; review the scan report" }
            failure  { echo "FAILURE — see the stage log above; any partial rollout was reverted" }
            always   { cleanWs() }
        }
    }
}
