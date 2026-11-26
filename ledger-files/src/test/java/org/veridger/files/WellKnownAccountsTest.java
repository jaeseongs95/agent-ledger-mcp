// SPDX-License-Identifier: AGPL-3.0-or-later

package org.veridger.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R9-D0095-A: the well-known accounts of Windows are found by their SID and not by an English name; the Windows API turns the SID
 * into the name of this host, which the file system of this host looks up. R9-D0095-B: the decisions of the product follow the names
 * that the host gives, whatever language they are in (a host that is not English is played by a table of names).
 */
class WellKnownAccountsTest {

    private static final FileSystem HOST = FileSystems.getDefault();

    /** A Windows in German, as far as the names go: none of them is the English name. */
    private static final WellKnownAccounts GERMAN = account -> switch (account) {
        case SERVICE -> "NT-AUTORITÄT\\LOKALER DIENST";
        case SYSTEM -> "NT-AUTORITÄT\\SYSTEM";
        case ADMINISTRATORS -> "VORDEFINIERT\\Administratoren";
    };

    private static boolean hasAcls() {
        return FileKind.of(HOST) == FileKind.ACL;
    }

    @Test
    @DisplayName("R9-D0095-A the SIDs are the well-known ones: S-1-5-19 LocalService, S-1-5-18 SYSTEM, S-1-5-32-544 Administrators")
    void theSidsAreTheWellKnownOnes() {
        assertThat(WellKnownAccount.SERVICE.sid()).isEqualTo("S-1-5-19");
        assertThat(WellKnownAccount.SYSTEM.sid()).isEqualTo("S-1-5-18");
        assertThat(WellKnownAccount.ADMINISTRATORS.sid()).isEqualTo("S-1-5-32-544");
    }

    @Test
    @DisplayName("R9-D0095-A on Windows each well-known account has a name on this host, and the file system looks that name up as a real account")
    void eachSidIsAnAccountOfThisHost() throws IOException {
        Assumptions.assumeTrue(hasAcls(), "the Windows API is called on a file system with ACLs only");
        UserPrincipalLookupService lookup = HOST.getUserPrincipalLookupService();
        Map<WellKnownAccount, UserPrincipal> found = new EnumMap<>(WellKnownAccount.class);

        for (WellKnownAccount account : WellKnownAccount.values()) {
            String name = WellKnownAccounts.host().nameOf(account);
            assertThat(name).as(account.name()).isNotBlank();
            found.put(account, lookup.lookupPrincipalByName(name));
            assertThat(found.get(account).getName()).as("the name of the principal of %s", account).isEqualToIgnoringCase(name);
        }
        assertThat(found.values()).as("three different accounts").doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("R9-D0095-A on Windows the service account principal is the principal of the name of its SID, and SYSTEM and Administrators are administrative by SID")
    void theProductJudgesByTheSid() throws IOException {
        Assumptions.assumeTrue(hasAcls(), "the Windows API is called on a file system with ACLs only");
        UserPrincipalLookupService lookup = HOST.getUserPrincipalLookupService();
        WellKnownAccounts host = WellKnownAccounts.host();

        assertThat(ServicePrincipalAcl.principal(HOST))
                .isEqualTo(lookup.lookupPrincipalByName(host.nameOf(WellKnownAccount.SERVICE)));
        assertThat(ServicePrincipalAcl.isAdministrative(lookup.lookupPrincipalByName(host.nameOf(WellKnownAccount.SYSTEM)))).isTrue();
        assertThat(ServicePrincipalAcl.isAdministrative(lookup.lookupPrincipalByName(host.nameOf(WellKnownAccount.ADMINISTRATORS))))
                .isTrue();
        assertThat(ServicePrincipalAcl.isAdministrative(ServicePrincipalAcl.principal(HOST))).as("LocalService is not").isFalse();
        assertThat(ServicePrincipalAcl.isAdministrative(lookup.lookupPrincipalByName("Everyone"))).as("Everyone is not").isFalse();
    }

    @Test
    @DisplayName("R9-D0095-A a SID that no account has is an IOException that names the function and the status, not a name")
    void anUnknownSidIsRefused() {
        Assumptions.assumeTrue(hasAcls(), "the Windows API is called on a file system with ACLs only");

        assertThatThrownBy(() -> new WindowsAccountNames().nameOfSid("S-1-5-21-1-2-3-424242"))
                .isInstanceOf(IOException.class).hasMessageContaining("LookupAccountSidW").hasMessageContaining("status");
        assertThatThrownBy(() -> new WindowsAccountNames().nameOfSid("not a sid"))
                .isInstanceOf(IOException.class).hasMessageContaining("ConvertStringSidToSidW");
    }

    @Test
    @DisplayName("R9-D0095-B a host whose account names are not English: the administrative principals are the German ones and not the English ones")
    void aHostWithOtherNamesIsJudgedBySid() throws IOException {
        assertThat(ServicePrincipalAcl.isAdministrative(new Named("NT-AUTORITÄT\\SYSTEM"), GERMAN)).isTrue();
        assertThat(ServicePrincipalAcl.isAdministrative(new Named("VORDEFINIERT\\Administratoren"), GERMAN)).isTrue();
        assertThat(ServicePrincipalAcl.isAdministrative(new Named("vordefiniert\\administratoren"), GERMAN)).as("case").isTrue();
        assertThat(ServicePrincipalAcl.isAdministrative(new Named("NT-AUTORITÄT\\LOKALER DIENST"), GERMAN)).isFalse();
        assertThat(ServicePrincipalAcl.isAdministrative(new Named("NT AUTHORITY\\SYSTEM"), GERMAN))
                .as("the English name belongs to nobody on this host: no name is written in the product").isFalse();
        assertThat(ServicePrincipalAcl.isAdministrative(new Named("BUILTIN\\Administrators"), GERMAN)).isFalse();
    }

    @Test
    @DisplayName("R9-D0095-B the service account is looked up by the name the host gives it, whatever language it is in")
    void theServiceAccountIsLookedUpByTheHostsName() throws IOException {
        Named service = new Named("NT-AUTORITÄT\\LOKALER DIENST");
        FileSystem german = new FileSystemWithViews(HOST, FileSystemWithViews.WINDOWS, Map.of(service.getName(), service));

        assertThat(ServicePrincipalAcl.principal(german, WellKnownAccount.SERVICE, GERMAN)).isSameAs(service);
        assertThatThrownBy(() -> ServicePrincipalAcl.principal(german, WellKnownAccount.SERVICE,
                account -> "NT AUTHORITY\\LocalService")).isInstanceOf(UserPrincipalNotFoundException.class);
    }

    /** A principal that is only a name. */
    private record Named(String name) implements UserPrincipal {

        @Override
        public String getName() {
            return name;
        }
    }
}
