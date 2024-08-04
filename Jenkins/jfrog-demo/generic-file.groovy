def server

node() {
    stage('Artifactory configuration') {
        server = Artifactory.server 'JFrogChina-Server'
        buildInfo = Artifactory.newBuildInfo()
        buildInfo.env.capture = true
    }
    
    stage('Download artifacts'){
        def downloadSpec = """{
         "files": [
            {
              "pattern": "slash-generic-dev-local/test2.zip",
              "target": "/Users/jingyil/work/test/generic/"
            }
         ]
        }"""
        server.download spec: downloadSpec 
    }
}
