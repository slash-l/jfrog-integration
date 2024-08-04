import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def user_apikey

withCredentials([string(credentialsId: 'platform-key', variable: 'secret_text')]) {
    user_apikey = "${secret_text}"
}

node {
    def artiServer
    def buildInfo
    def rtMaven
    def warVersion

    stage('Prepare') {
        artiServer = Artifactory.server('arti-platform')
        buildInfo = Artifactory.newBuildInfo()
        buildInfo.env.capture = true
        rtMaven = Artifactory.newMavenBuild()
    }

    stage('SCM') {
        git branch: 'master', url: 'https://github.com/xingao0803/maven-pipeline.git'
    }

    stage('Update Version') {
        warVersion = "1-${BUILD_NUMBER}"
        sh "sed -i 's/-BUILD_NUMBER-/${warVersion}/g' pom.xml **/pom.xml"
        sh "git add pom.xml **/pom.xml"
        sh "git commit -m \"#HYG-4 Update Version to ${BUILD_NUMBER}\""
        sh "git remote rm origin"
        withCredentials([usernamePassword(credentialsId: 'gaoxin_github', passwordVariable: 'password', usernameVariable: 'username')]) {
            sh "git remote add origin https://$username:$password@github.com/xingao0803/maven-pipeline.git"
        }
        sh "git push -u origin master"
    }

    //执行maven构建打包
    stage('Maven Build'){
        rtMaven.resolver server: artiServer, releaseRepo: 'maven-pipeline-virtual', snapshotRepo: 'maven-pipeline-virtual'
        rtMaven.deployer server: artiServer, releaseRepo: 'maven-pipeline-dev-local', snapshotRepo: 'maven-pipeline-dev-local'

        rtMaven.tool = 'maven'
        rtMaven.run pom: './pom.xml', goals: 'clean install', buildInfo: buildInfo


        def config = """{
                    "version": 1,
                    "issues": {
                            "trackerName": "JIRA",
                            "regexp": "#([\\w\\-_\\d]+)\\s(.+)",
                            "keyGroupIndex": 1,
                            "summaryGroupIndex": 2,
                            "trackerUrl": "http://jira.jfrogchina.com:8081/browse/",
                            "aggregate": "true",
                            "aggregationStatus": "Released"
                    }
                }"""


        buildInfo.issues.collect(artiServer, config)

        artiServer.publishBuildInfo buildInfo

    }

// 	stage('Add JIRAResult'){
// 	    def returnList = getRequirements();

// 	    if (returnList.size() != 0) {
//             def requirements = returnList[0];
//             echo "requirements : ${requirements}"
//             def jira_urls = returnList[1];
//             def revisionIds = getRevisionIds();
//             echo "revisionIds : ${revisionIds}"

//             commandJira = "curl -H \"X-JFrog-Art-Api: ${user_apikey}\" -X PUT \""+artiServer.url+"/api/storage/maven-pipeline-dev-local/org/jfrog/test/multi3/"+warVersion+"/multi3-"+warVersion+".war?properties=project.issues="+ requirements +";project.issues.urls="+jira_urls+";project.revisionIds="+ revisionIds +"\" ";
// 	        process = [ 'bash', '-c', commandJira].execute().text
// 	    }

//   }

    stage('Sonar Test') {
        // Sonar scan
        if (Sonar_SCAN == "YES") {
            def scannerHome = tool 'sonarClient';
            withSonarQubeEnv('sonar') {
                sh "${scannerHome}/bin/sonar-runner -Dsonar.projectKey=${JOB_BASE_NAME} -Dsonar.sources=./multi3/src/main -Dsonar.tests=./multi3/src/test"
            }
        }
    }
    //添加sonar扫描结果到包上
    stage("Add SonarResult"){
        if (Sonar_SCAN == "YES") {
            //获取sonar扫描结果
            def getSonarIssuesCmd = "curl  GET -v http://47.93.114.82:9000/api/issues/search?componentRoots=${JOB_BASE_NAME}";
            process = [ 'bash', '-c', getSonarIssuesCmd].execute().text

            //增加sonar扫描结果到artifactory
            def jsonSlurper = new JsonSlurper()
            def issueMap = jsonSlurper.parseText(process);
            echo "issueMap:"+issueMap
            echo "Total:"+issueMap.total
            commandSonar = "curl -H \"X-JFrog-Art-Api: ${user_apikey}\" -X PUT \""+artiServer.url+"/api/storage/maven-pipeline-dev-local/org/jfrog/test/multi3/"+warVersion+"/multi3-"+warVersion+".war?properties=quality.gate.sonarUrl=http://47.93.114.82:9000/dashboard/index/${JOB_BASE_NAME};quality.gate.sonarIssue="+issueMap.total+"\" ";
            echo commandSonar
            process = [ 'bash', '-c', commandSonar].execute().text
        }
    }

    stage('xray scan'){
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
            commandText = "curl  -H \"X-JFrog-Art-Api: ${user_apikey}\" -X PUT \""+artiServer.url+"/api/storage/maven-pipeline-dev-local/org/jfrog/test/multi3/"+warVersion+"/multi3-"+warVersion+".war?properties=Xray=scanned;Xray_issues_number="+xrayresult.alerts[0].issues.size()+"\" ";
            process = [ 'bash', '-c', commandText].execute().text
        }
    }


    stage('Test Approval'){
        commandText = "curl  -H \"X-JFrog-Art-Api: ${user_apikey}\" -X PUT \""+artiServer.url+"/api/storage/maven-pipeline-dev-local/org/jfrog/test/multi3/"+warVersion+"/multi3-"+warVersion+".war?properties=test.approve=true\" ";
        process = [ 'bash', '-c', commandText].execute().text

    }

    //promotion操作，进行包的升级
    stage('promotion'){
        def promotionConfig = [
                'buildName'   : buildInfo.name,
                'buildNumber' : buildInfo.number,
                'targetRepo'  : 'maven-pipeline-release-local',
                'comment': 'this is the promotion comment',
                'sourceRepo':'maven-pipeline-dev-local',
                'status': 'Released',
                'includeDependencies': false,
                'failFast': true,
                'copy': true
        ]
        artiServer.promote promotionConfig

    }

    stage('Deploy') {
        def downloadSpec = """ {
            "files": [
                {
                    "aql": {
                        "items.find": {
                            "repo": "maven-pipeline-release-local",
                            "name": {"\$match": "multi3-${warVersion}.war"},
                            "@quality.gate.sonarIssue":{"\$lt":"4"},
                            "@test.approve":{"\$eq":"true"}
                        }
                    },
                    "flat": "true"
                } 
            ]
        } """
//                            "@Xray":{"\$eq":"scanned"},

        artiServer.download(downloadSpec)
        sleep 3
        sh "ls -l multi3-*.war"
        echo "Deploy Completed!"
    }

    stage('Clean Up'){
        sh "sed -i 's/1-${BUILD_NUMBER}/-BUILD_NUMBER-/g' pom.xml **/pom.xml"
        sh "git add pom.xml **/pom.xml"
        sh "git commit -m \"HYG-20 Recover Version to original\""
        sh "git remote rm origin"
        withCredentials([usernamePassword(credentialsId: 'gaoxin_github', passwordVariable: 'password', usernameVariable: 'username')]) {
            sh "git remote add origin https://$username:$password@github.com/xingao0803/maven-pipeline.git"
        }
        sh "git push -u origin master"
        sh "rm -rf *"
        sh "rm -rf .sonar .git"
    }

}

@NonCPS
def getRequirements(){
    def reqIds = "";
    def urls = "";
    def jira_url = "http://jira.jfrogchina.com:8081/browse/";

    final changeSets = currentBuild.changeSets
    echo 'changeset count:'+ changeSets.size().toString()
    if ( changeSets.size() == 0 ) {
        return reqIds;
    }
    final changeSetIterator = changeSets.iterator()
    while (changeSetIterator.hasNext()) {
        final changeSet = changeSetIterator.next();
        def logEntryIterator = changeSet.iterator();
        while (logEntryIterator.hasNext()) {
            final logEntry = logEntryIterator.next()
            def patten = ~/#[\w\-_\d]+/;
            def matcher = (logEntry.getMsg() =~ patten);
            def count = matcher.getCount();
            for (int i = 0; i < count; i++){
                reqIds += matcher[i].replace('#', '') + ","
                urls += jira_url + matcher[i].replace('#', '') + ","
            }
        }
    }

    def returnList = [ reqIds[0..-2], urls[0..-2] ]
    return returnList;
}

@NonCPS
def getRevisionIds(){
    def reqIds = "";
    final changeSets = currentBuild.changeSets
    if ( changeSets.size() == 0 ) {
        return reqIds;
    }
    final changeSetIterator = changeSets.iterator()
    while (changeSetIterator.hasNext()) {
        final changeSet = changeSetIterator.next();
        def logEntryIterator = changeSet.iterator();
        while (logEntryIterator.hasNext()) {
            final logEntry = logEntryIterator.next()
            reqIds += logEntry.getRevision() + ","
        }
    }
    return reqIds[0..-2]
}
