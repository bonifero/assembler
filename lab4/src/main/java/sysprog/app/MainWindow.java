package sysprog.app;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.JTextComponent;

import sysprog.shared.Assembler;
import sysprog.shared.dto.CommandDto;
import sysprog.shared.exceptions.AssemblerException;
import sysprog.shared.helpers.Numbers;
import sysprog.shared.helpers.Parser;
import sysprog.shared.structure.SymbolicName;

public class MainWindow extends JFrame {
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 14);
    private static final DefaultHighlighter.DefaultHighlightPainter CURRENT_LINE =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 236, 140));

    private final Assembler assembler = new Assembler();

    private final JTextField loadAddressField = textField(true);
    private final JTextArea sourceCodeArea = textArea(true);
    private final JTextArea commandsArea = textArea(true);

    private final JTextField progressField = textField(false);
    private final JTextArea objectCodeArea = textArea(false);
    private final JTextArea symbolTableArea = textArea(false);
    private final JTextArea errorsArea = errorArea();

    private final JButton runButton = new JButton("Полный проход");
    private final JButton stepButton = new JButton("Шаг");
    private final JButton resetButton = new JButton("Сброс");
    private final JComboBox<String> samplesComboBox = new JComboBox<>(Samples.ALL.keySet().toArray(new String[0]));

    public MainWindow() {
        super("Однопросмотровый ассемблер в абсолютном формате (ЛР 4)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        sourceCodeArea.setText(Samples.DEFAULT_SAMPLE);
        commandsArea.setText(formatCommands());

        JPanel columns = new JPanel(new GridLayout(1, 2, 8, 0));
        columns.add(buildSourcePanel());
        columns.add(buildResultPanel());

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(columns, BorderLayout.CENTER);
        root.add(buildButtonsPanel(), BorderLayout.SOUTH);
        setContentPane(root);

        runButton.addActionListener(e -> runToEnd());
        stepButton.addActionListener(e -> runStep());
        resetButton.addActionListener(e -> reset());
        samplesComboBox.addActionListener(e -> loadSelectedSample());
        resetOnChange(sourceCodeArea);
        resetOnChange(commandsArea);
        resetOnChange(loadAddressField);

        reset();

        setSize(1400, 850);
        setLocationRelativeTo(null);
        setExtendedState(MAXIMIZED_BOTH);
    }

    private JPanel buildSourcePanel() {
        JPanel column = new JPanel(new BorderLayout(0, 5));
        column.add(titled("Адрес загрузки (16-ричный, пусто — из операнда START)", loadAddressField), BorderLayout.NORTH);
        column.add(stack(
                weighted(titled("Исходный текст (подсвечена следующая строка)", scroll(sourceCodeArea)), 3),
                weighted(titled("Таблица кодов операций (имя, код, длина — 16-ричные)", scroll(commandsArea)), 2)),
                BorderLayout.CENTER);
        return column;
    }

    private JPanel buildResultPanel() {
        JPanel column = new JPanel(new BorderLayout(0, 5));
        column.add(titled("Ход ассемблирования", progressField), BorderLayout.NORTH);
        column.add(stack(
                weighted(titled("Двоичный код (" + Assembler.UNRESOLVED_OPERAND
                        + " — адрес метки ещё не известен)", scroll(objectCodeArea)), 4),
                weighted(titled("Таблица символических имен (имя, адрес, адреса команд, ждущих метку)",
                        scroll(symbolTableArea)), 2),
                weighted(titled("Ошибки", scroll(errorsArea)), 1)),
                BorderLayout.CENTER);
        return column;
    }

    private JPanel buildButtonsPanel() {
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(runButton);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(stepButton);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(resetButton);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(new JLabel("Выбор примера: "));
        buttons.add(samplesComboBox);
        return buttons;
    }

    private void reset() {
        objectCodeArea.setText("");
        symbolTableArea.setText("");
        errorsArea.setText("");

        try {
            assembler.setCommands(Parser.parseCommandTable(commandsArea.getText()));
            assembler.reset(Parser.parseCode(sourceCodeArea.getText()), loadAddressField.getText());
        } catch (AssemblerException ex) {
            showError(ex);
            progressField.setText("Ассемблер не готов к работе: исправьте ТКО или адрес загрузки");
            highlightLine(-1);
            return;
        }

        setRunning(true);
        showProgress();
    }

    private void runStep() {
        try {
            assembler.step();
        } catch (AssemblerException ex) {
            showError(ex);
        } catch (RuntimeException ex) {
            showError(ex);
        }
        showState();
    }

    private void runToEnd() {
        try {
            assembler.run();
        } catch (AssemblerException ex) {
            showError(ex);
        } catch (RuntimeException ex) {
            showError(ex);
        }
        showState();
    }

    private void showState() {
        objectCodeArea.setText(String.join("\n", assembler.getObjectCode()));
        symbolTableArea.setText(assembler.getSymbolTable().stream()
                .map(MainWindow::formatSymbol)
                .collect(Collectors.joining("\n")));
        if (assembler.isFinished())
            setRunning(false);
        showProgress();
    }

    private static String formatSymbol(SymbolicName symbol) {
        String address = symbol.isDefined() ? Numbers.hex(symbol.address(), 6) : "------";
        String pending = symbol.pendingReferences().stream()
                .map(a -> Numbers.hex(a, 6))
                .collect(Collectors.joining(" "));
        return (symbol.name() + " " + address + " " + pending).strip();
    }

    private void showError(AssemblerException ex) {
        errorsArea.setText("Ошибка: " + ex.getMessage());
        setRunning(false);
    }

    private void showError(RuntimeException ex) {
        errorsArea.setText("Непредвиденная ошибка: " + ex);
        setRunning(false);
    }

    private void setRunning(boolean running) {
        runButton.setEnabled(running);
        stepButton.setEnabled(running);
    }

    private void showProgress() {
        int done = assembler.getNextLine();
        int total = assembler.getLineCount();

        if (!errorsArea.getText().isEmpty())
            progressField.setText("Остановлено с ошибкой на строке " + Math.min(done + 1, total) + " из " + total
                    + ". Исправьте исходный текст или ТКО — ассемблер сбросится сам");
        else if (assembler.isFinished())
            progressField.setText("Готово: обработаны все строки (" + total + "), двоичный код сформирован");
        else
            progressField.setText("Обработано строк: " + done + " из " + total);

        highlightLine(assembler.isFinished() ? -1 : done);
    }

    private void highlightLine(int sourceLine) {
        sourceCodeArea.getHighlighter().removeAllHighlights();
        if (sourceLine < 0)
            return;

        String[] lines = sourceCodeArea.getText().split("\n", -1);
        int offset = 0;
        int nonBlank = 0;
        for (String line : lines) {
            if (!line.isBlank()) {
                if (nonBlank == sourceLine) {
                    try {
                        sourceCodeArea.getHighlighter().addHighlight(offset, offset + line.length(), CURRENT_LINE);
                    } catch (BadLocationException ignored) {
                    }
                    return;
                }
                nonBlank++;
            }
            offset += line.length() + 1;
        }
    }

    private void loadSelectedSample() {
        String sample = Samples.ALL.get((String) samplesComboBox.getSelectedItem());
        if (sample != null)
            sourceCodeArea.setText(sample);
    }

    private void resetOnChange(JTextComponent component) {
        component.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                SwingUtilities.invokeLater(MainWindow.this::reset);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                SwingUtilities.invokeLater(MainWindow.this::reset);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
            }
        });
    }

    private String formatCommands() {
        return assembler.getCommands().stream()
                .map(CommandDto::from)
                .map(c -> c.name() + " " + c.code() + " " + c.length())
                .collect(Collectors.joining("\n"));
    }

    private static JTextArea textArea(boolean editable) {
        JTextArea area = new JTextArea();
        configure(area, editable);
        area.setTabSize(4);
        return area;
    }

    private static JTextArea errorArea() {
        JTextArea area = textArea(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    private static JTextField textField(boolean editable) {
        JTextField field = new JTextField();
        configure(field, editable);
        return field;
    }

    private static void configure(JTextComponent component, boolean editable) {
        component.setFont(MONO);
        component.setEditable(editable);
    }

    private static JScrollPane scroll(JTextArea area) {
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(null);
        return scroll;
    }

    private static JComponent titled(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private static JComponent weighted(JComponent component, int weight) {
        component.putClientProperty("weight", weight);
        return component;
    }

    private static JPanel stack(JComponent... parts) {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.weightx = 1;
        constraints.insets = new Insets(0, 0, 5, 0);
        for (JComponent part : parts) {
            constraints.weighty = (Integer) part.getClientProperty("weight");
            panel.add(part, constraints);
        }
        return panel;
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> new MainWindow().setVisible(true));
    }
}
