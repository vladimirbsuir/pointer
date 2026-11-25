package dev.pointer.agent.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DesktopCoordinatesTests {

    private static final DesktopGeometry DEFAULT = new DesktopGeometry(1280, 800, 1280, 800);
    private static final DesktopGeometry LARGE = new DesktopGeometry(1920, 1200, 1280, 800);

    @Test
    void pixelsAreTheIdentityAtTheDefaults() {
        var coordinates = new DesktopCoordinates(DEFAULT, false);

        assertThat(coordinates.screenX("x", 640)).isEqualTo(640);
        assertThat(coordinates.screenY("y", 799)).isEqualTo(799);
        assertThat(coordinates.modelX(640)).isEqualTo(640);
        assertThat(coordinates.mark(640, 360)).isEqualTo(Map.of("x", 640, "y", 360));
        assertThat(coordinates.screenX("x", 0)).isZero();
        assertThat(coordinates.screenX("x", 1279)).isEqualTo(1279);
    }

    @Test
    void theGridMapsToPixelsWithOneThousandOnTheLastPixel() {
        var coordinates = new DesktopCoordinates(DEFAULT, true);

        assertThat(coordinates.screenX("x", 0)).isZero();
        assertThat(coordinates.screenX("x", 1000)).isEqualTo(1279);
        assertThat(coordinates.screenY("y", 1000)).isEqualTo(799);
        assertThat(coordinates.screenX("x", 500)).isEqualTo(640);
        assertThat(coordinates.modelX(1279)).isEqualTo(1000);
        assertThat(coordinates.modelX(640)).isEqualTo(500);
        assertThat(coordinates.modelY(0)).isZero();
    }

    @Test
    void aLargeDisplayMapsThroughTheScaledImage() {
        var pixels = new DesktopCoordinates(LARGE, false);
        assertThat(pixels.screenX("x", 640)).isEqualTo(960);
        assertThat(pixels.screenY("y", 799)).isEqualTo(1199);
        assertThat(pixels.modelX(960)).isEqualTo(640);
        assertThat(pixels.modelY(1199)).isEqualTo(799);
        assertThat(pixels.mark(960, 600)).isEqualTo(Map.of("x", 640, "y", 400));

        var grid = new DesktopCoordinates(LARGE, true);
        assertThat(grid.screenX("x", 500)).isEqualTo(960);
        assertThat(grid.screenY("y", 1000)).isEqualTo(1199);
        assertThat(grid.modelX(960)).isEqualTo(500);
        assertThat(grid.mark(960, 1199)).isEqualTo(Map.of("x", 640, "y", 799));
    }

    @Test
    void valuesOutsideTheScreenshotFailInsteadOfBeingClamped() {
        var pixels = new DesktopCoordinates(DEFAULT, false);
        assertThatThrownBy(() -> pixels.screenX("x", 1280))
                .isInstanceOf(DesktopCoordinates.InvalidCoordinatesException.class)
                .hasMessageContaining("x is 1280")
                .hasMessageContaining("0 to 1279 horizontally");
        assertThatThrownBy(() -> pixels.screenY("y", -1))
                .isInstanceOf(DesktopCoordinates.InvalidCoordinatesException.class);
        assertThatThrownBy(() -> pixels.screenX("x", "left"))
                .isInstanceOf(DesktopCoordinates.InvalidCoordinatesException.class)
                .hasMessage("x must be a number");

        var grid = new DesktopCoordinates(DEFAULT, true);
        assertThatThrownBy(() -> grid.screenX("x", 1001))
                .isInstanceOf(DesktopCoordinates.InvalidCoordinatesException.class)
                .hasMessageContaining("0 to 1000 on each axis");
    }

    @Test
    void zoomRectanglesConvertBothWaysAndMustFitTheScreenshot() {
        var pixels = new DesktopCoordinates(LARGE, false);
        var screen = pixels.screenRect(Map.of("x", 100, "y", 50, "width", 200, "height", 100));
        assertThat(screen).isEqualTo(Map.of("x", 150, "y", 75, "width", 300, "height", 150));
        assertThat(pixels.modelRect(screen)).isEqualTo(Map.of("x", 100, "y", 50, "width", 200, "height", 100));
        assertThat(pixels.screenRect(Map.of("x", 1080, "y", 700, "width", 200, "height", 100)))
                .isEqualTo(Map.of("x", 1620, "y", 1050, "width", 300, "height", 150));
        assertThatThrownBy(() -> pixels.screenRect(Map.of("x", 1200, "y", 0, "width", 200, "height", 100)))
                .isInstanceOf(DesktopCoordinates.InvalidCoordinatesException.class);
        assertThatThrownBy(() -> pixels.screenRect(Map.of("x", 0, "y", 0, "width", 0, "height", 100)))
                .isInstanceOf(DesktopCoordinates.InvalidCoordinatesException.class);

        var grid = new DesktopCoordinates(DEFAULT, true);
        assertThat(grid.screenRect(Map.of("x", 500, "y", 500, "width", 500, "height", 500)))
                .isEqualTo(Map.of("x", 640, "y", 400, "width", 640, "height", 400));
    }

    @Test
    void theToolDescriptionsNameTheModelsConvention() {
        assertThat(DesktopTools.describe("desktop_click", "Click.", "pixels"))
                .isEqualTo("Click. " + DesktopTools.PIXELS_NOTE);
        assertThat(DesktopTools.describe("desktop_click", "Click.", "normalized"))
                .isEqualTo("Click. " + DesktopTools.GRID_NOTE);
        assertThat(DesktopTools.describe("desktop_type", "Type.", "normalized")).isEqualTo("Type.");
    }
}
