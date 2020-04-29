%global version 0.0.1
%global release 1
%global jar %{name}.jar
%define _optdir /opt

Name:           authfed
Version:        %{version}
Release:        %{release}%{?dist}
Summary:        Auth federation system for web-apps.

License:        Proprietary
URL:            http://www.authfed.com/

%description
Auth federation system for web-apps.

%define __jar_repack 0

%prep
make %{jar}

%pre
getent group %{name} >/dev/null || groupadd -r %{name}
getent passwd %{name} >/dev/null || \
    useradd -r -g %{name} -d %{_optdir}/%{name} -s /sbin/nologin %{name}
exit 0

%install
rm -rf %{buildroot}
install -d %{buildroot}%{_optdir}/%{name}
install -d %{buildroot}%{_var}/log/%{name}
install -d %{buildroot}%{_sysconfdir}/%{name}
install -d %{buildroot}%{_sysconfdir}/sysconfig
install -d %{buildroot}%{_unitdir}
install -p -m 0644 %{jar} %{buildroot}%{_optdir}/%{name}/%{jar}
install -p -m 0744 copy-pem-files.sh %{buildroot}%{_optdir}/%{name}/copy-pem-files.sh
install -p -m 0644 logback.xml %{buildroot}%{_sysconfdir}/%{name}/logback.xml
install -p -m 0644 sysconfig %{buildroot}%{_sysconfdir}/sysconfig/%{name}
install -p -m 0644 %{name}.service %{buildroot}%{_unitdir}/%{name}.service

%clean
rm -rf %{buildroot}

%files
%dir %attr(0755, %{name}, %{name}) %{_optdir}/%{name}
%dir %attr(0755, %{name}, %{name}) %{_var}/log/%{name}
%dir %attr(0755, %{name}, %{name}) %{_sysconfdir}/%{name}
%attr(0644, %{name}, %{name}) %{_optdir}/%{name}/%{jar}
%attr(0744, root, root) %{_optdir}/%{name}/copy-pem-files.sh
%attr(0644, %{name}, %{name}) %{_sysconfdir}/%{name}/logback.xml
%attr(0644, root, root) %{_sysconfdir}/sysconfig/%{name}
%attr(0644, root, root) %{_unitdir}/%{name}.service

%changelog
* Fri Apr 17 2020 James Davidson <jamesd3142@gmail.com> - 0.0.0-1
- Initial RPM release
