package sysprog.app;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.nio.file.Path;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileNameExtensionFilter;
import sysprog.shared.helpers.ObjectModuleFile;
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
import sysprog.shared.AddressingMode;
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

    private final JComboBox<AddressingMode> addressingModeComboBox = new JComboBox<>(AddressingMode.values());
    private final JTextArea relocationTableArea = textArea(false);
    private final JTextArea sourceCodeArea = textArea(true);
    private final JTextArea commandsArea = textArea(true);

    private final JTextField progressField = textField(false);
    private final JTextArea objectCodeArea = textArea(false);
    private final JTextArea symbolTableArea = textArea(false);
    private final JTextArea externalNamesArea = textArea(false);
    private final JTextArea externalReferencesArea = textArea(false);
    private final JTextArea sectionsArea = textArea(false);
    private final JTextArea errorsArea = errorArea();

    private final JButton saveButton = new JButton("Сохранить...");
    private final JButton runButton = new JButton("Полный проход");
    private final JButton stepButton = new JButton("Шаг");
    private final JButton resetButton = new JButton("Сброс");
    private final JComboBox<String> samplesComboBox = new JComboBox<>(Samples.ALL.keySet().toArray(new String[0]));

    public MainWindow() {
        super("Однопросмотровый ассемблер в полном перемещаемом формате (ЛР 6)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        sourceCodeArea.setText(Samples.DEFAULT_SAMPLE);
        commandsArea.setText(formatCommands());

        JPanel columns = new JPanel(new GridLayout(1, 3, 8, 0));
        columns.add(buildSourcePanel());
        columns.add(buildTablesPanel());
        columns.add(buildResultPanel());

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(columns, BorderLayout.CENTER);
        root.add(buildButtonsPanel(), BorderLayout.SOUTH);
        setContentPane(root);

        runButton.addActionListener(e -> runToEnd());
        stepButton.addActionListener(e -> runStep());
        resetButton.addActionListener(e -> reset());
        saveButton.addActionListener(e -> saveObjectModule());
        samplesComboBox.addActionListener(e -> loadSelectedSample());
        resetOnChange(sourceCodeArea);
        resetOnChange(commandsArea);
        addressingModeComboBox.addActionListener(e -> reset());

        reset();

        setSize(1400, 850);
        setLocationRelativeTo(null);
        setExtendedState(MAXIMIZED_BOTH);
    }

    private JPanel buildSourcePanel() {
        JPanel column = new JPanel(new BorderLayout(0, 5));
        column.add(titled("Режим адресации (START без операнда или 0)", addressingModeComboBox), BorderLayout.NORTH);
        column.add(stack(
                weighted(titled("Исходный текст (подсвечена следующая строка)", scroll(sourceCodeArea)), 3),
                weighted(titled("Таблица кодов операций (имя, код, длина — 16-ричные)", scroll(commandsArea)), 2)),
                BorderLayout.CENTER);
        return column;
    }

    private JPanel buildTablesPanel() {
        return stack(
                weighted(titled("Секции: имя, длина, точка входа (после завершения)", scroll(sectionsArea)), 1),
                weighted(titled("ТСИ: имя, адрес, ВИ 1/0, [секция], ожидающие команды", scroll(symbolTableArea)), 3),
                weighted(titled("Внешние имена: имя, адрес, [секция]", scroll(externalNamesArea)), 2),
                weighted(titled("Внешние ссылки: имя, [секция]", scroll(externalReferencesArea)), 2));
    }

    private JPanel buildResultPanel() {
        JPanel column = new JPanel(new BorderLayout(0, 5));
        column.add(titled("Ход ассемблирования", progressField), BorderLayout.NORTH);
        column.add(stack(
                weighted(titled("Таблица настройки: адрес команды, внешняя ссылка, [секция]",
                        scroll(relocationTableArea)), 2),
                weighted(titled("Объектные модули секций (H, D, R, M, T, E)", scroll(objectCodeArea)), 5),
                weighted(titled("Ошибки", scroll(errorsArea)), 1)), BorderLayout.CENTER);
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
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(saveButton);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(new JLabel("Выбор примера: "));
        buttons.add(samplesComboBox);
        return buttons;
    }

    private void reset() {
        saveButton.setEnabled(false);
        externalNamesArea.setText("");
        externalReferencesArea.setText("");
        sectionsArea.setText("");
        objectCodeArea.setText("");
        relocationTableArea.setText("");
        symbolTableArea.setText("");
        errorsArea.setText("");

        try {
            assembler.setCommands(Parser.parseCommandTable(commandsArea.getText()));
            assembler.reset(Parser.parseCode(sourceCodeArea.getText()));
            assembler.setAddressingMode((AddressingMode) addressingModeComboBox.getSelectedItem());
        } catch (AssemblerException ex) {
            showError(ex);
            progressField.setText("Ассемблер не готов к работе: исправьте ТКО или исходный текст");
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
        relocationTableArea.setText(assembler.getRelocationTable().stream()
                .map(e -> Numbers.hex(e.address(), 6) + " "
                        + (e.externalReference() == null ? "—" : e.externalReference()) + " [" + e.section() + "]")
                .collect(Collectors.joining("\n")));
        externalNamesArea.setText(assembler.getExternalNames().stream()
                .map(s -> s.name() + " " + (s.isDefined() ? Numbers.hex(s.address(), 6) : "------")
                        + " [" + s.section() + "]").collect(Collectors.joining("\n")));
        externalReferencesArea.setText(java.util.stream.IntStream.range(0, assembler.getExternalReferences().size())
                .mapToObj(i -> assembler.getExternalReferences().get(i) + " ["
                        + assembler.getExternalReferenceSections().get(i) + "]").collect(Collectors.joining("\n")));
        sectionsArea.setText(assembler.getSections().stream()
                .map(s -> s.name() + " " + Numbers.hex(s.length(), 6) + " " + Numbers.hex(s.entryAddress(), 6))
                .collect(Collectors.joining("\n")));
        saveButton.setEnabled(assembler.isFinished() && errorsArea.getText().isEmpty());
        if (assembler.isFinished())
            setRunning(false);
        showProgress();
    }

    private static String formatSymbol(SymbolicName symbol) {
        String address = symbol.isDefined() ? Numbers.hex(symbol.address(), 6) : "------";
        String pending = symbol.pendingReferences().stream()
                .map(a -> Numbers.hex(a, 6))
                .collect(Collectors.joining(" "));
        return (symbol.name() + " " + address + " " + (symbol.external() ? 1 : 0)
                + " [" + symbol.section() + "] " + pending).strip();
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
            progressField.setText("Секция " + assembler.getCurrentSection() + ": " + done + " из " + total);

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

    private void saveObjectModule() {
        if (!assembler.isFinished() || !errorsArea.getText().isEmpty()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Сохранение объектных модулей секций");
        chooser.setFileFilter(new FileNameExtensionFilter("Текстовый файл (*.txt)", ObjectModuleFile.EXTENSION));
        chooser.setSelectedFile(Path.of(assembler.getSections().get(0).name() + ".txt").toFile());
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = ObjectModuleFile.withExtension(chooser.getSelectedFile().toPath());
        if (path.toFile().exists() && JOptionPane.showConfirmDialog(this,
                "Файл " + path.getFileName() + " уже существует. Перезаписать?", "Сохранение",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            ObjectModuleFile.save(path, assembler.getObjectCode());
            JOptionPane.showMessageDialog(this, "Объектные модули сохранены в " + path);
        } catch (AssemblerException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Ошибка сохранения", JOptionPane.ERROR_MESSAGE);
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
