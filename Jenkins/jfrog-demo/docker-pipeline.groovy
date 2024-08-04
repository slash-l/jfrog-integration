import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def user_apikey

withCredentials([string(credentialsId: 'platform-key', variable: 'secret_text')]) {
    user_apikey = "${secret_text}"
}

node {
    def artiServer
    def buildInfo
    def rtDocker

    def latestVersion

    def dockerFramework


    stage('Prepare') {
        artiServer = Artifactory.server('arti-platform')
        rtDocker = Artifactory.docker server: artiServer
    }

    stage('SCM') {

        git branch: 'master', url: 'https://gitee.com/wq237wq/Guestbook-microservices-k8s.git'
    }

    stage ('GetLatestJar') {
        dir ('guestbook-service') {
            sh "chmod 777 query-aql"
            def command = "./query-aql ${user_apikey} ${artiServer.url} newOneMaven.aql"
            latestVersion = sh returnStdout: true , script: command
            /*  "results" : [ {
                    "repo" : "libs-release-local",
                    "path" : "org/wangqing/guestbook-microservices-k8s/guestbook-service/3.1.8",
                    "name" : "guestbook-service-3.1.8.jar",
            */

            latestVersion = latestVersion.trim()
            echo "latestVersion:" + latestVersion
            try {
                println "Get latest available Maven App"
                def mavenAppDownload = """{
                    "files": [
                      {
                        "pattern": "libs-release-local/org/wangqing/guestbook-microservices-k8s/guestbook-service/${latestVersion}/guestbook-service-*.jar",
                        "target": "guestbook-service.jar",
                        "flat": "true"
                      }
                    ]
                }"""
                artiServer.download(mavenAppDownload, buildInfo )
            } catch (Exception e) {
                println "Caught Exception during resolution. Message ${e.message}"
                throw e
            }
        }
    }

    stage ('GetDockerFramework') {

        dockerFramework = "${ARTDOCKER_REGISTRY}/${RELEASE_REPO}/docker-framework:latest"

    }

    stage ('Build & Deploy Docker') {
        dir ('guestbook-service') {
            sh "sed -i 's&--DOCKER-FRAMEWORK--&${dockerFramework}&' IDCFDockerfile"
            tagDockerApp = "${ARTDOCKER_REGISTRY}/${DEV_REPO}/guestbook-service:${env.BUILD_NUMBER}"

            println "Docker App Build"
            docker.build(tagDockerApp,"-f IDCFDockerfile .")
            println "Docker push" + tagDockerApp + " : " + DEV_REPO
            buildInfo = rtDocker.push(tagDockerApp, DEV_REPO, buildInfo)
            println "Docker Buildinfo"
            artiServer.publishBuildInfo buildInfo
        }
    }

    stage('Test') {
        def commandText = "curl  -H \"X-JFrog-Art-Api: ${user_apikey}\" -X PUT \""+artiServer.url+"/api/storage/${DEV_REPO}/guestbook-service/${env.BUILD_NUMBER}?properties=Functest=pass;BuildTag=${env.BUILD_NUMBER}\" ";
        sh commandText
    }

    //Scan Build Artifacts in Xray
    stage('Xray Scan') {
        if (XRAY_SCAN == "YES") {
            def xrayConfig = [
                    'buildName'     : env.JOB_NAME,
                    'buildNumber'   : env.BUILD_NUMBER,
                    'failBuild'     : false
            ]

            def xrayResults = artiServer.xrayScan xrayConfig
            echo xrayResults as String

            def jsonSlurper = new JsonSlurper()
            def xrayresult = jsonSlurper.parseText(xrayResults.toString())
            echo "Xray Result total issues:" + xrayresult.alerts[0].issues.size()
            commandText = "curl  -H \"X-JFrog-Art-Api: ${user_apikey}\" -X PUT \""+artiServer.url+"/api/storage/${DEV_REPO}/guestbook-service/${env.BUILD_NUMBER}?properties=Xray=scanned;Xray_issues_number="+xrayresult.alerts[0].issues.size()+"\" ";
            process = [ 'bash', '-c', commandText].execute().text
        } else {
            println "No Xray scan performed. To enable set XRAY_SCAN = YES"
        }
    }


}