#mvn clean install -DskipTests karaf:kar && \
#scp target/*.kar robin@testvm-ubuntu:~/openhab/addons
mvn clean install -DskipTests && \
scp target/org.openhab.binding.stiebelheatpump-4.2.1.jar robin@server.windey.home:/home/robin/docker/openhab_data/addons/kar-extract/repository/org/openhab/addons/bundles/org.openhab.binding.stiebelheatpump/4.2.1/org.openhab.binding.stiebelheatpump-4.2.1.jar

#bundle:uninstall <id>
#bundle:install file:/openhab/addons/kar-extract/repository/org/openhab/addons/bundles/org.openhab.binding.stiebelheatpump/4.2.1/org.openhab.binding.stiebelheatpump-4.2.1.jar
#bundle:start <id>