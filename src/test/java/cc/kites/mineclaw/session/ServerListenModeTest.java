package cc.kites.mineclaw.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServerListenModeTest {
    @Test
    void modeIsGlobalIdempotentAndResettable() {
        ServerListenMode mode = new ServerListenMode();

        assertThat(mode.isEnabled()).isFalse();
        assertThat(mode.enable()).isTrue();
        assertThat(mode.enable()).isFalse();
        assertThat(mode.isEnabled()).isTrue();
        assertThat(mode.disable()).isTrue();
        assertThat(mode.disable()).isFalse();

        mode.enable();
        mode.reset();
        assertThat(mode.isEnabled()).isFalse();
    }
}
