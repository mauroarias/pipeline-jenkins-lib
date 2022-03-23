def call(body) {
    def params= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = params
    body()

    def agentName

    pipeline {
        agent any
        options {
            timestamps()
            disableConcurrentBuilds()
        }
        stages {
            stage('load library') {
                steps {
                    script { 
                        project = env.JOB_NAME.split('/')[0]
                        projectName = sh(script: "echo '${project}' | sed 's/PRJ-//'", returnStdout: true)
                        new org.mauro.LibLoader().loadLib()
                        templateLib.configUsingManifest()
                        agentName = templateLib.getTemplateAgent()
                    }
                }
            }
            stage('Setup') {
                parallel {
                    stage('load vars') {
                        agent {
                            docker {
                                image "${agentName}"
                            }
                        }                
                        steps {
                            script { 
                                appversion = templateLib.getAppVersion()
                                serviceName = templateLib.getAppServiceName()
                                artifactId = templateLib.getArtifactId()
                                groupId = templateLib.getgroupId()
                                app = "${artifactId}-${appversion}"
                                image = "${artifactId}:${appversion}"
                                sh "echo 'project name: ${projectName}'"
                                sh "echo 'app version: ${appversion}'"
                                sh "echo 'service description: ${serviceName}'"
                                sh "echo 'artifact id: ${artifactId}'"
                                sh "echo 'group id: ${groupId}'"
                                sh "echo 'app: ${app}'"
                                sh "echo 'image: ${image}'"
                            }
                        }
                    }
                    stage('Validation') {
                        steps {
                            script {
                                if (env.CHANGE_TARGET.equals('stage') && !env.CHANGE_TARGET.equals('develop')) {
                                    error("You must merge to 'stage' from 'develop'...!")
                                }
                                if (env.CHANGE_TARGET.equals('prod') && !env.CHANGE_TARGET.equals('stage')) {
                                    error("You must merge to 'prod' from 'stage'...!")
                                }
                            }
                        }
                    }
                }
            }
            stage('Build Application') {
                when {
                    expression { doWeBuild() }
                }
                agent {
                    docker {
                        image "${agentName}"
                    }
                }                
                steps {
                    script {
                        templateLib.build()
                        outFolder = templateLib.getOutFolder()
                        jenkinsLib.stash('codeBuilt', "${outFolder}**/*", '')
                        jenkinsLib.archiveArtifacts("${outFolder}${app}.jar")
                        templateLib.publishTestCoverageReport()
                    }
                }
            }
            stage('Build Docker Image') {
                when {
                    expression { doWeBuild() }
                }
                steps {
                    script {
                        unstash 'codeBuilt'
                        sh "docker build -t ${dockerLib.getDockerRepositoryDev()}/${image} ." 
                    }
                }
            }
            stage('Quality') {
                when {
                    expression { doWeBuild() }
                }
                parallel {
                    stage('Test') {
                        steps {
                            script {
                                sh "echo 'add tests'"
                            }
                        }
                    }
                    stage('Sonar') {
                        stages {
                            stage('Create project') {
                                environment {
                                    SONAR_CRED = credentials('user-pass-credential-sonar-credentials')
                                    SONAR_TOKEN = credentials('sonar-token')
                                }
                                steps {
                                    script {
                                        sonarLib.createProjetIfNotExists(artifactId)
                                    }
                                }
                            }
                            stage('push artifact') {
                                environment {
                                    SONAR_CRED = credentials('user-pass-credential-sonar-credentials')
                                    SONAR_TOKEN = credentials('sonar-token')
                                }
                                agent {
                                    docker {
                                        image "${agentName}"
                                        args '−−network host'
                                    }
                                }                
                                steps {
                                    script {
                                        sonarLib.pushSonarArtifact(artifactId)
                                    }
                                }
                            }
                            stage('check analysis') {
                                environment {
                                    SONAR_CRED = credentials('user-pass-credential-sonar-credentials')
                                    SONAR_TOKEN = credentials('sonar-token')
                                }
                                steps {
                                    script {
                                        sonarLib.qualityGate(artifactId)
                                    }
                                }
                            }
                        }
                    }       
                    stage('Dependency check') {
                        steps {
                            script {
                                sh "echo 'add dependency check'"
                            }
                        }
                    }       
                    stage('Mutation tests') {
                        steps {
                            script {
                                sh "echo 'add mutation test'"
                            }
                        }
                    }       
                }
            }
            stage('Pushing') {
                when {
                    branch 'develop'
                }
                parallel {
                    stage('Image') {
                        steps {
                            script {
                                sh "docker push ${dockerLib.getDockerRepositoryDev()}/${image}"
                                sh "docker image tag ${dockerLib.getDockerRepositoryDev()}/${image} ${dockerLib.getDockerRepositoryDev()}/${artifactId}:latest"
                            }
                        }
                    }
                }
            }
        }
        post {
            // Clean after build
            always {
                cleanWs()
                deleteDir()
            }
        }
    }
}

def doWeBuild () {
    return (!env.BRANCH_NAME.equals('prod') && !env.BRANCH_NAME.equals('stage') && !isPrToStageOrProd())
}

def isPrToStageOrProd () {
    return (env.CHANGE_TARGET.equals('prod') || env.CHANGE_TARGET.equals('stage'))
}