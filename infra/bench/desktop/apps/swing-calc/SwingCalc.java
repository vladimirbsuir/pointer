import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

public final class SwingCalc {

    private static Path checkPath;
    private static int clicks;

    public static void main(String[] arguments) {
        checkPath = Path.of(arguments.length > 0 ? arguments[0] : "/tmp/swing-calc.json");
        SwingUtilities.invokeLater(SwingCalc::show);
    }

    private static void show() {
        var frame = new JFrame("Swing Calc");
        frame.setLayout(new FlowLayout());
        var first = new JTextField("2", 6);
        first.getAccessibleContext().setAccessibleName("First number");
        var second = new JTextField("3", 6);
        second.getAccessibleContext().setAccessibleName("Second number");
        var plus = new JButton("Plus");
        var result = new JLabel("Result: none");
        plus.addActionListener(event -> {
            clicks++;
            var text = sum(first.getText(), second.getText());
            result.setText("Result: " + text);
            write(text);
        });
        frame.add(first);
        frame.add(second);
        frame.add(plus);
        frame.add(result);
        frame.setSize(460, 160);
        frame.setLocation(120, 120);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);
        write("none");
        System.out.println("swing-calc ready");
    }

    private static String sum(String first, String second) {
        try {
            return Long.toString(Long.parseLong(first.trim()) + Long.parseLong(second.trim()));
        } catch (NumberFormatException exception) {
            return "error";
        }
    }

    private static void write(String result) {
        var json = "{\"result\":\"" + result + "\",\"clicks\":" + clicks + "}";
        var temporary = checkPath.resolveSibling(checkPath.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            Files.move(temporary, checkPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
