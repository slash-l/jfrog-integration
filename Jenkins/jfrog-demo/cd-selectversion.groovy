def user_apikey

withCredentials([string(credentialsId: 'platform-key', variable: 'secret_text')]) {
    user_apikey = "${secret_text}"
}

node {
    def artiServer
    def mavenList
    def released

    stage('Prepare') {
        artiServer = Artifactory.server('arti-platform')
    }

    stage('SCM') {
        git branch: 'master', url: 'https://github.com/xingao0803/maven-cd-pipeline.git'
    }

    stage('Install JFrog CLI') {
        try {
            def downloadSpec = """{
                "files": [
                    {
                        "pattern": "config-local/cli/jfrog",
                        "target": "jfrog",
                        "flat":"true"
                    }
                ]
            }"""

            artiServer.download (downloadSpec)
            if (fileExists('jfrog')) {
                println "Downloaded JFrog Cli Tool"
            } else {
                println "Missing JFrog Cli Tool"
                sh 'ls -l'
                throw new FileNotFoundException("Missing Tools")
            }
        } catch (Exception e) {
            println "Caught exception during resolution.  Message ${e.message}"
            throw e
        }

        sh "chmod 777 ./jfrog"
        sh "./jfrog rt config arti-server --url ${artiServer.url} --user admin --password ${user_apikey}"
    }

    stage('Search Candidate Packages') {
        //sh "chmod +x cli-query"
        def command = "./cli-query arti-server"

        /* cli-query
        jfrog rt s --server-id $1 --spec ./mavens.spec --sort-by created --sort-order desc --limit 3
                | grep path | awk -F "/" '{print $NF}' | awk -F "\"" '{print $1}'

        */

        /* mavens.spec
            {
                "files": [
                {
                    "aql": {
                        "items.find": {
                        "repo": "maven-pipeline-release-local",
                        "name":{"$match":"multi3-*.war"},
                        "@quality.gate.sonarIssue":{"$lt":"4"},
                        "@test.approve":{"$eq":"true"}
                        }
                    }
                }]
            }
        */

        mavenList = sh returnStdout: true , script: command

        echo "Candidate Releases:"
        echo mavenList
    }

    stage('Select Release Package') {
        released = input id: 'select',
                message: '请选择要发布的版本',
                ok: '确认',
                parameters: [choice(choices: mavenList, name: 'release_choice')],
                submitter: 'admin'

        echo "1:"
        echo released
    }

    stage('Download Release Package') {
        sh "./jfrog rt dl --server-id=arti-server --flat=true maven-pipeline-release-local/**/${released} ./"
    }

    stage('Deploy the Package') {
        sh "ls multi3*.war"

        def path = sh returnStdout: true , script: "pwd"
        path = path.trim()

        //Deploying
        def deployCmd = "ansible 127.0.0.1 -c local -m copy -a 'src=${path}/${released}  dest=/home/tomcat/apache-tomcat-8.5.40/webapps/demo.war'"
        echo deployCmd
        process = sh returnStdout: true , script: deployCmd
        echo process

        //Add Deployment Metadata
        sh "./jfrog rt sp --server-id=arti-server \"maven-pipeline-release-local/**/${released}\" \"deploy.tool=ansible;deploy.env=127.0.0.1\""


        //Restarting Tomcat
        def stopCmd = "ansible 127.0.0.1 -c local -m shell -a '/home/tomcat/apache-tomcat-8.5.40/bin/shutdown.sh'"
        echo stopCmd
        [ 'bash', '-c', stopCmd].execute().text

        def startCmd = "ansible 127.0.0.1 -c local -m shell -a '/home/tomcat/apache-tomcat-8.5.40/bin/startup.sh'"
        echo startCmd
        [ 'bash', '-c', startCmd].execute().text

        //ClearUp
        sh "rm multi3*.war"
        sh "rm jfrog"
    }



}
