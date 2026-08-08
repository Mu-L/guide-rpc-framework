package github.javaguide.extention;

import github.javaguide.extension.ExtensionLoader;
import github.javaguide.extension.SPI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtensionLoaderValidationTest {

    @Test
    void shouldRejectConfiguredClassThatDoesNotImplementSpi() {
        ExtensionLoader<InvalidExtension> loader =
                ExtensionLoader.getExtensionLoader(InvalidExtension.class);

        assertThrows(IllegalStateException.class,
                () -> loader.getExtension("invalid"));
    }

    @Test
    void shouldRejectMalformedSpiEntry() {
        ExtensionLoader<MalformedExtension> loader =
                ExtensionLoader.getExtensionLoader(MalformedExtension.class);

        assertThrows(IllegalStateException.class,
                () -> loader.getExtension("anything"));
    }
}

@SPI
interface InvalidExtension {
}

@SPI
interface MalformedExtension {
}
