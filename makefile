SDIR = ./src/
DDIR = ./bin/
RDIR = ./data/
JC = javac

.SUFFIXES: .java .class
.java.class:
	$(JC) -sourcepath $(SDIR) -d $(DDIR) $*.java

CLASSES = \
	$(SDIR)client/MyClient.java \
	$(SDIR)client/Main.java \
	$(SDIR)server/MyServer.java \
	$(SDIR)server/Main.java

default: install

init:
	mkdir -p $(DDIR)

install: init classes
	cp $(RDIR)cityzip.csv $(DDIR)
	@echo 'java client.Main $$1 $$2' > $(DDIR)myclient
	@echo 'java server.Main $$1' > $(DDIR)myserver
	chmod +x $(DDIR)myclient $(DDIR)myserver
	@echo 'build complete!'

classes: $(CLASSES:.java=.class)

clean:
	$(RM) -r $(DDIR)client/*.class \
	$(DDIR)server/*.class \
	$(DDIR)cityzip.csv \
	$(DDIR)myclient \
	$(DDIR)myserver
	@echo 'done!'

dpclean:
	$(RM) -r $(DDIR)
	@echo 'done!'