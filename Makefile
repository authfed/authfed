PACKAGE = authfed
JAR = $(PACKAGE).jar

EXTRA_DIST = Makefile config/*

RPM_TOPDIR := $(HOME)/rpmbuild

$(JAR):
	clojure -A:uberjar

clean:
	rm -vf $(JAR)

rpm: $(JAR)
	mkdir -v -p $(RPM_TOPDIR)/{BUILD,RPMS,SOURCES,SPECS,SRPMS}
	cp -v $(PACKAGE).spec $(RPM_TOPDIR)/SPECS/$(PACKAGE).spec
	cp -v $(JAR) $(EXTRA_DIST) $(RPM_TOPDIR)/BUILD/
	rpmbuild -ba $(RPM_TOPDIR)/SPECS/$(PACKAGE).spec
	cp -v $(RPM_TOPDIR)/RPMS/x86_64/$(PACKAGE)-* .

rpmclean:
	rm -vf $(RPM_TOPDIR)/SPECS/$(PACKAGE).spec
	rm -vf $(RPM_TOPDIR)/BUILD/$(JAR)
	rm -vf $(RPM_TOPDIR)/RPMS/x86_64/$(PACKAGE)-*

.PHONY = clean rpm rpmclean
