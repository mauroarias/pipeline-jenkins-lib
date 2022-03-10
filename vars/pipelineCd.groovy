def call(body) {
    def params= [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = params
    body()

    pipeline {
        agent any
        options {
            timestamps()
            disableConcurrentBuilds()
        }
        stages {
            stage('Setup') {
                parallel {
                    stage('Initialize') {
                        steps {
                            script { 
                                projectName = env.JOB_NAME.split('/')[0]
                                sh "echo env.JOB_NAME"
                                sh "echo 'appName: ${appName}'"
                                sh "echo 'project name: ${projectName}' | sed 's/PRJ-//g'"

                                def loadingLib = new org.mauro.LibLoader()
                                loadingLib.loadLib()

                                appversion = build.getAppVersion()
                                serviceName = build.getAppServiceName()
                                artifactId = build.getArtifactId()
                                groupId = build.getgroupId()
                                app = "${serviceName}-${appversion}"
                            }
                        }
                    }
                    stage('Branch validation') {
                        steps {
                            script { 
                                if (env.CHANGE_TARGET.equals("${const.STAGE_BRANCH}") && !env.CHANGE_TARGET.equals("${const.DEV_BRANCH}")) {
                                    error("You must merge to '${const.STAGE_BRANCH}' from '${const.DEV_BRANCH}'...!")
                                }
                                if (env.CHANGE_TARGET.equals("${const.PROD_BRANCH}") && !env.CHANGE_TARGET.equals("${const.STAGE_BRANCH}")) {
                                    error("You must merge to '${const.PROD_BRANCH}' from '${const.STAGE_BRANCH}'...!")
                                }
                            }
                        }
                    }
                }
            }
            stage('Build Application') {
                agent {
                    docker {
                        image 'maven:3.8.1-adoptopenjdk-11'
                    }
                }                
                when {
                    expression { weBuild() }
                }
                steps {
                    sh 'mvn clean package'
                    jenkins.stash('codeBuilt', 'target/*')
                    jenkins.archiveArtifacts("target/${app}")
                    jenkins.publishHTML('code coverage', 'code coverage report', 'target/jacoco-report/', 'index.html')
                }
            }
            stage('Build Docker Image') {
                when {
                    expression { weBuild() }
                }
                steps {
                    echo '=== Building Docker Image ==='
                    sh 'mvn fabric8:build'
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

def weBuild () {
    return (!env.BRANCH_NAME.equals("${const.PROD_BRANCH}") && !env.BRANCH_NAME.equals("${const.STAGE_BRANCH}") && !isPrToStageOrProd())
}

def isPrToStageOrProd () {
    return (env.CHANGE_TARGET.equals("${const.PROD_BRANCH}") || env.CHANGE_TARGET.equals("${const.STAGE_BRANCH}"))
}