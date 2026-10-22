package sysprog.app;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.JTextComponent;

import sysprog.shared.AddressingMode;
import sysprog.shared.Assembler;
import sysprog.shared.dto.CommandDto;
import sysprog.shared.exceptions.AssemblerException;
import sysprog.shared.helpers.Numbers;
import sysprog.shared.helpers.ObjectModuleFile;
import sysprog.shared.helpers.Parser;

public class MainWindow extends JFrame {
    private static final Font MONO = new Font(Font.MONOSPACED, Font.PLAIN, 14);

    private final Assembler assembler = new Assembler();

    private final JTextArea sourceCodeArea = textArea(true);
    private final JTextArea commandsArea = textArea(true);

    private final JTextArea firstPassArea = textArea(false);
    private final JTextArea symbolTableArea = textArea(false);
    private final JTextArea firstPassErrorsArea = errorArea();
    private final JTextField entryAddressField = textField(false);

    private final JTextField headerField = textField(false);
    private final JTextArea relocationTableArea = textArea(false);
    private final JTextArea externalNamesArea = textArea(false);
    private final JTextArea externalReferencesArea = textArea(false);
    private final JTextArea objectCodeArea = textArea(false);
    private final JTextArea secondPassErrorsArea = errorArea();

    private final JButton firstPassButton = new JButton("Первый проход");
    private final JButton secondPassButton = new JButton("Второй проход");
    private final JButton saveButton = new JButton("Сохранить...");
    private final JComboBox<AddressingMode> addressingModeComboBox = new JComboBox<>(AddressingMode.values());
    private final JComboBox<String> samplesComboBox = new JComboBox<>(Samples.ALL.keySet().toArray(new String[0]));

    private List<String> objectCode = new ArrayList<>();

    public MainWindow() {
        super("Двухпросмотровый ассемблер в полном перемещаемом формате (ЛР 3)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);

        sourceCodeArea.setText(Samples.DEFAULT_SAMPLE);
        commandsArea.setText(formatCommands());

        JPanel columns = new JPanel(new GridLayout(1, 3, 8, 0));
        columns.add(buildSourcePanel());
        columns.add(buildFirstPassPanel());
        columns.add(buildSecondPassPanel());

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        root.add(columns, BorderLayout.CENTER);
        root.add(buildButtonsPanel(), BorderLayout.SOUTH);
        setContentPane(root);

        firstPassButton.addActionListener(e -> runFirstPass());
        secondPassButton.addActionListener(e -> runSecondPass());
        saveButton.addActionListener(e -> saveObjectModule());
        samplesComboBox.addActionListener(e -> loadSelectedSample());
        addressingModeComboBox.setSelectedItem(assembler.getAddressingMode());
        addressingModeComboBox.addActionListener(e -> changeAddressingMode());
        secondPassButton.setEnabled(false);
        saveButton.setEnabled(false);

        setSize(1400, 850);
        setLocationRelativeTo(null);
        setExtendedState(MAXIMIZED_BOTH);
    }

    private JPanel buildSourcePanel() {
        return stack(
                weighted(titled("Исходный текст", scroll(sourceCodeArea)), 3),
                weighted(titled("Таблица кодов операций (имя, код, длина — 16-ричные)", scroll(commandsArea)), 2));
    }

    private JPanel buildFirstPassPanel() {
        JPanel column = new JPanel(new BorderLayout(0, 5));
        column.add(titled("Точка входа (адрес после END)", entryAddressField), BorderLayout.NORTH);
        column.add(stack(
                weighted(titled("Вспомогательная таблица", scroll(firstPassArea)), 3),
                weighted(titled("Таблица символических имен (имя, адрес, внешнее имя 1/0)", scroll(symbolTableArea)), 2),
                weighted(titled("Ошибки первого прохода", scroll(firstPassErrorsArea)), 1)),
                BorderLayout.CENTER);
        return column;
    }

    private JPanel buildSecondPassPanel() {
        JPanel column = new JPanel(new BorderLayout(0, 5));
        column.add(titled("Заголовок объектного модуля", headerField), BorderLayout.NORTH);
        JPanel tables = new JPanel(new GridLayout(1, 2, 5, 0));
        tables.add(titled("Таблица настройки (адрес, внешняя ссылка)", scroll(relocationTableArea)));
        tables.add(titled("Внешние имена (имя, адрес)", scroll(externalNamesArea)));

        JPanel saveRow = new JPanel();
        saveRow.add(saveButton);

        column.add(stack(
                weighted(tables, 2),
                weighted(titled("Внешние ссылки", scroll(externalReferencesArea)), 1),
                weighted(titled("Двоичный код", scroll(objectCodeArea)), 4),
                weighted(titled("Ошибки второго прохода", scroll(secondPassErrorsArea)), 1)),
                BorderLayout.CENTER);
        column.add(saveRow, BorderLayout.SOUTH);
        return column;
    }

    private JPanel buildButtonsPanel() {
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(firstPassButton);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(secondPassButton);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(new JLabel("Режим адресации: "));
        buttons.add(addressingModeComboBox);
        buttons.add(Box.createHorizontalStrut(20));
        buttons.add(new JLabel("Выбор примера: "));
        buttons.add(samplesComboBox);
        return buttons;
    }

    private void runFirstPass() {
        clearFirstPass();
        clearSecondPass();

        try {
            assembler.setCommands(Parser.parseCommandTable(commandsArea.getText()));

            List<String> firstPassCode = assembler.firstPass(Parser.parseCode(sourceCodeArea.getText()));

            firstPassArea.setText(String.join("\n", firstPassCode).strip());
            symbolTableArea.setText(assembler.getSymbolTable().stream()
                    .map(s -> s.name() + " " + Numbers.hex(s.address(), 6) + " " + (s.external() ? 1 : 0))
                    .collect(Collectors.joining("\n")));
            entryAddressField.setText(Numbers.hex(assembler.getEntryAddress(), 6));
        } catch (AssemblerException ex) {
            firstPassErrorsArea.setText("Ошибка: " + ex.getMessage());
        } catch (RuntimeException ex) {
            firstPassErrorsArea.setText("Непредвиденная ошибка: " + ex);
        }

        secondPassButton.setEnabled(firstPassErrorsArea.getText().isEmpty());
    }

    private void runSecondPass() {
        clearSecondPass();
        secondPassButton.setEnabled(false);

        try {
            List<String> code = assembler.secondPass(Parser.parseCode(firstPassArea.getText()));

            headerField.setText(code.get(0));
            relocationTableArea.setText(assembler.getRelocationTable().stream()
                    .map(entry -> Numbers.hex(entry.address(), 6)
                            + (entry.externalReference() == null ? "" : " " + entry.externalReference()))
                    .collect(Collectors.joining("\n")));
            externalNamesArea.setText(assembler.getExternalNames().stream()
                    .map(s -> s.name() + " " + Numbers.hex(s.address(), 6))
                    .collect(Collectors.joining("\n")));
            externalReferencesArea.setText(String.join("\n", assembler.getExternalReferences()));
            objectCodeArea.setText(String.join("\n", code));
            objectCode = code;
            saveButton.setEnabled(true);
        } catch (AssemblerException ex) {
            secondPassErrorsArea.setText("Ошибка: " + ex.getMessage());
        } catch (RuntimeException ex) {
            secondPassErrorsArea.setText("Непредвиденная ошибка: " + ex);
        }
    }

    private void saveObjectModule() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Сохранение объектного модуля");
        chooser.setFileFilter(new FileNameExtensionFilter("Текстовый файл (*.txt)", ObjectModuleFile.EXTENSION));
        chooser.setSelectedFile(Path.of(headerProgramName() + "." + ObjectModuleFile.EXTENSION).toFile());
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION)
            return;

        Path path = ObjectModuleFile.withExtension(chooser.getSelectedFile().toPath());
        if (path.toFile().exists() && JOptionPane.showConfirmDialog(this, "Файл " + path.getFileName()
                + " уже существует. Перезаписать?", "Сохранение", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION)
            return;

        try {
            ObjectModuleFile.save(path, objectCode);
            JOptionPane.showMessageDialog(this, "Объектный модуль сохранён в файл " + path);
        } catch (AssemblerException ex) {
            secondPassErrorsArea.setText("Ошибка: " + ex.getMessage());
        }
    }

    private String headerProgramName() {
        String[] header = headerField.getText().split(" ");
        return header.length > 1 ? header[1] : "program";
    }

    private void loadSelectedSample() {
        String sample = Samples.ALL.get((String) samplesComboBox.getSelectedItem());
        if (sample == null) return;

        sourceCodeArea.setText(sample);
        clearFirstPass();
        clearSecondPass();
        secondPassButton.setEnabled(false);
    }

    private void changeAddressingMode() {
        assembler.setAddressingMode((AddressingMode) addressingModeComboBox.getSelectedItem());
        clearFirstPass();
        clearSecondPass();
        secondPassButton.setEnabled(false);
    }

    private void clearFirstPass() {
        firstPassArea.setText("");
        symbolTableArea.setText("");
        firstPassErrorsArea.setText("");
        entryAddressField.setText("");
    }

    private void clearSecondPass() {
        headerField.setText("");
        relocationTableArea.setText("");
        externalNamesArea.setText("");
        externalReferencesArea.setText("");
        objectCode = new ArrayList<>();
        saveButton.setEnabled(false);
        objectCodeArea.setText("");
        secondPassErrorsArea.setText("");
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
