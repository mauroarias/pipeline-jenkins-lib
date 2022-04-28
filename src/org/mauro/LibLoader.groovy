package org.mauro

def loadLib () {
    def libVersion = 'wip-0.2.0'
    sh "echo 'loading lib version: ${libVersion}'"
    library identifier: "jenkins-share-lib@${libVersion}", retriever: 
        modernSCM(
            [$class: 'GitSCMSource',
            remote: 'https://github.com/mauroarias/jenkins-share-lib.git'])
}

return this