
ShareFiles:
	javac ShareFiles.java

all: clean ShareFilesApplet ShareFiles ShareFiles-jar swing

clean:
	rm -rf *.class core

ShareFiles-jar: clean ShareFiles
	jar cf ShareFiles.jar *.class

applet: swing ShareFilesApplet

ShareFilesApplet:
	javac ShareFilesApplet.java

swing: ShareFiles-jar
	rm -rf swing-jar
	mkdir swing-jar
	cd swing-jar ; jar xf ../swing.jar
	cd swing-jar ; jar xf ../ShareFiles.jar
	rm -rf swing-jar/META-INF
	cd swing-jar ; jar cf ../ShareFilesSwing.jar .
	rm -rf swing-jar

archive:
	cd .. ; \
	tar zcf ShareFiles.tar.gz ShareFiles 

server:
	java ShareFiles server

client:
	java ShareFiles

