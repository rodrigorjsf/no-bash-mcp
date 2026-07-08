package dev.nobash.domain.forge;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OriginRemoteParser} (PRD-6 S1, #99, AC "origin parsing covers SSH and HTTPS
 * remote forms"). Pure, offline — no git, no filesystem.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OriginRemoteParserTest {

    @Test
    void parses_the_scp_like_ssh_form() {
        Optional<OriginRemote> parsed = OriginRemoteParser.parse("git@github.com:octo-org/hello-world.git");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().host()).isEqualTo("github.com");
        assertThat(parsed.get().owner()).isEqualTo("octo-org");
        assertThat(parsed.get().repo()).isEqualTo("hello-world");
        assertThat(parsed.get().slug()).isEqualTo("octo-org/hello-world");
    }

    @Test
    void parses_the_ssh_scheme_form() {
        Optional<OriginRemote> parsed = OriginRemoteParser.parse("ssh://git@github.com/octo-org/hello-world.git");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().host()).isEqualTo("github.com");
        assertThat(parsed.get().slug()).isEqualTo("octo-org/hello-world");
    }

    @Test
    void parses_the_https_form_with_dot_git_suffix() {
        Optional<OriginRemote> parsed = OriginRemoteParser.parse("https://github.com/octo-org/hello-world.git");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().host()).isEqualTo("github.com");
        assertThat(parsed.get().owner()).isEqualTo("octo-org");
        assertThat(parsed.get().repo()).isEqualTo("hello-world");
    }

    @Test
    void parses_the_https_form_without_dot_git_suffix() {
        Optional<OriginRemote> parsed = OriginRemoteParser.parse("https://github.com/octo-org/hello-world");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().repo()).isEqualTo("hello-world");
    }

    @Test
    void parses_an_ssh_host_alias_verbatim_so_the_allowlist_can_reject_it() {
        // SSH host aliases (github.com-work) are parsed verbatim; the allowlist match (not the parser)
        // decides whether the host is permitted — the repo override is the escape hatch (D62(5)).
        Optional<OriginRemote> parsed = OriginRemoteParser.parse("git@github.com-work:octo-org/hello-world.git");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().host()).isEqualTo("github.com-work");
    }

    @Test
    void a_ghes_https_host_is_parsed() {
        Optional<OriginRemote> parsed = OriginRemoteParser.parse("https://ghe.example.com/team/service.git");

        assertThat(parsed).isPresent();
        assertThat(parsed.get().host()).isEqualTo("ghe.example.com");
        assertThat(parsed.get().slug()).isEqualTo("team/service");
    }

    @Test
    void null_input_is_empty() {
        assertThat(OriginRemoteParser.parse(null)).isEmpty();
    }

    @Test
    void blank_input_is_empty() {
        assertThat(OriginRemoteParser.parse("   ")).isEmpty();
    }

    @Test
    void a_remote_without_a_repo_segment_is_empty() {
        assertThat(OriginRemoteParser.parse("https://github.com/only-owner")).isEmpty();
    }

    @Test
    void a_garbage_string_is_empty() {
        assertThat(OriginRemoteParser.parse("not a url at all")).isEmpty();
    }
}
