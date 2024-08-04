node {
    def mvnHome
    
    stage('Testing') { // for display purposes
        sh 'jf -v'
        sh 'jf rt ping'
    }
    
    stage('Create RB') {
        sh "jf ds rbc slash-rb2 2.0.${env.BUILD_NUMBER} 'slash-generic-dev-local/**/*'"
    }
    
    stage('Sign RB') {
        // sh 'jf ds rbs --passphrase="Jfr0gchina!" slash-rb2 2.0.${env.BUILD_NUMBER}'
        
        sh """curl -uslash:Slashliu0709! -H "Accept: application/json" -H "Content-Type: application/json" -H "X-GPG-PASSPHRASE: Jfr0gchina!" -XPOST https://demo.jfrogchina.com/distribution/api/v1/release_bundle/slash-rb2/2.0.${env.BUILD_NUMBER}/sign --data '{"signing_key_alias": "gpg-1657037584883"}' """
    }
    
    stage('Distribute RB to Edge') {
        sh "jf ds rbd slash-rb2 2.0.${env.BUILD_NUMBER} --site='Edge'"
    }
    
}
