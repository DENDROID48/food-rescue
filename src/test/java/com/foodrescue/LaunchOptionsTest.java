package com.foodrescue;

import com.foodrescue.exception.InvalidLaunchOptionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LaunchOptionsTest {

    @Test
    void shortOptionsBecomeSpringSettingsAndOthersPassThrough() {
        LaunchOptions options = LaunchOptions.parse(
                "--mode=files", "--data-dir=my-data", "--port=8081", "--spring.profiles.active=dev");

        assertThat(options.springArguments()).containsExactly(
                "--foodrescue.storage.mode=files",
                "--foodrescue.storage.data-dir=my-data",
                "--server.port=8081",
                "--spring.profiles.active=dev");
        assertThat(options.helpRequested()).isFalse();
    }

    @Test
    void demoModeMeansMemory() {
        assertThat(LaunchOptions.parse("--mode=DEMO").springArguments())
                .containsExactly("--foodrescue.storage.mode=memory");
    }

    @Test
    void helpIsRecognised() {
        assertThat(LaunchOptions.parse("--help").helpRequested()).isTrue();
    }

    @Test
    void invalidValuesAreRefusedWithAClearMessage() {
        assertThatThrownBy(() -> LaunchOptions.parse("--mode=cloud"))
                .isInstanceOf(InvalidLaunchOptionException.class).hasMessageContaining("Unknown mode 'cloud'");
        assertThatThrownBy(() -> LaunchOptions.parse("--port=abc"))
                .isInstanceOf(InvalidLaunchOptionException.class).hasMessageContaining("whole number")
                .hasCauseInstanceOf(NumberFormatException.class);
        assertThatThrownBy(() -> LaunchOptions.parse("--port=70000"))
                .isInstanceOf(InvalidLaunchOptionException.class).hasMessageContaining("between 1 and 65535");
        assertThatThrownBy(() -> LaunchOptions.parse("--data-dir="))
                .isInstanceOf(InvalidLaunchOptionException.class);
        assertThatThrownBy(() -> LaunchOptions.parse("--mode", "files"))
                .isInstanceOf(InvalidLaunchOptionException.class).hasMessageContaining("equals sign");
    }
}
