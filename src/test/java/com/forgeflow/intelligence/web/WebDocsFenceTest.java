package com.forgeflow.intelligence.web;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The SSRF fence on its own. DNS is faked, so an allowlisted name can be made
 * to resolve to an internal address - the attack an allowlist alone misses.
 */
class WebDocsFenceTest {

    private static InetAddress ip(String s) {
        try {
            return InetAddress.getByName(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static WebDocs with(Map<String, String> dns) {
        Function<String, InetAddress[]> resolver = host -> new InetAddress[]{ip(dns.getOrDefault(host, "151.101.1.1"))};
        return new WebDocs(true, "developer.mozilla.org,react.dev", "https://developer.mozilla.org", false, false,
                1_000_000, 8_000, resolver);
    }

    @Test
    void onlyHttpsOnAllowlistedHostsAtTheDefaultPort() {
        WebDocs web = with(Map.of());
        assertThat(web.check("https://developer.mozilla.org/en-US/docs/Web/API/fetch").getHost()).isEqualTo("developer.mozilla.org");
        assertThat(web.check("https://www.react.dev/learn").getHost()).isEqualTo("www.react.dev");
        assertThatThrownBy(() -> web.check("http://developer.mozilla.org/")).hasMessageContaining("Only https");
        assertThatThrownBy(() -> web.check("file:///etc/passwd")).hasMessageContaining("Only https");
        assertThatThrownBy(() -> web.check("https://evil.example/")).hasMessageContaining("isn't on the documentation allowlist");
        assertThatThrownBy(() -> web.check("https://developer.mozilla.org.evil.example/")).hasMessageContaining("allowlist");
        assertThatThrownBy(() -> web.check("https://notreact.dev/")).hasMessageContaining("allowlist");
        assertThatThrownBy(() -> web.check("https://169.254.169.254/latest/meta-data/")).hasMessageContaining("allowlist");
        assertThatThrownBy(() -> web.check("https://admin:pw@developer.mozilla.org/")).hasMessageContaining("user name or password");
        assertThatThrownBy(() -> web.check("https://developer.mozilla.org:8443/")).hasMessageContaining("default https port");
    }

    @Test
    void anAllowedNameThatResolvesInsideIsRefused() {
        for (String inside : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "::1", "fc00::1", "fe80::1"}) {
            WebDocs web = with(Map.of("developer.mozilla.org", inside));
            assertThatThrownBy(() -> web.check("https://developer.mozilla.org/"))
                    .as(inside).hasMessageContaining("resolves to an internal address");
        }
    }

    @Test
    void thePageIsReducedToItsMainText() {
        String html = """
                <html><head><style>body{color:red}</style><script>stealCookies()</script></head>
                <body><nav>Home | Docs | Blog</nav><header>MDN</header>
                <main><h1>Array.prototype.at()</h1><p>The <code>at()</code> method takes an integer &amp; returns
                the item at that index &lt;negative counts from the end&gt;.</p><!-- hidden --></main>
                <footer>(c) Mozilla</footer></body></html>""";
        String text = WebDocs.mainText(html);
        assertThat(text).contains("Array.prototype.at()").contains("takes an integer & returns")
                .contains("<negative counts from the end>")
                .doesNotContain("stealCookies").doesNotContain("Home | Docs").doesNotContain("color:red")
                .doesNotContain("(c) Mozilla").doesNotContain("hidden");
    }
}
