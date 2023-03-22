#chaotix -> breaks with poll1
#java -XX:AutoBoxCacheMax=65536 -XX:-DontCompileHugeMethods -XX:+AlwaysCompileLoopMethods -Dkey.config.file=key.config.1p -Dhelios.32x.sh2.poll.detect=false -Dtinylog.configuration=./res/tinylog.properties -Djinput.enable=true -Djinput.native.location=lib -jar helios32x-23.0322.jar "$@"

#fifa96 -> cycles=18 poll0 or cycles 12 poll1
#brutal -> cycles=12
#java -XX:AutoBoxCacheMax=65536 -XX:-DontCompileHugeMethods -XX:+AlwaysCompileLoopMethods -Dkey.config.file=key.config.1p -Dhelios.32x.sh2.cycles=12 -Dtinylog.configuration=./res/tinylog.properties -Djinput.enable=true -Djinput.native.location=lib -jar helios32x-23.0322.jar "$@"
