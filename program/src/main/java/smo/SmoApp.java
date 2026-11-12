package smo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Locale;

/** Minimal visual step-by-step view: sources, buffer, devices only. */
public final class SmoApp extends JFrame {
    private Simulation.Config config = new Simulation.Config(3, 2, 4, 1.2, 1.0, 2.0, 40, 17);
    private Simulation simulation;

    private final JLabel statusLabel = new JLabel();
    private final JTextArea eventText = new JTextArea(2, 30);
    private final JButton nextButton = new JButton("Следующий шаг  →");
    private final DefaultTableModel sources = model("Источник", "Сген.", "Отказы", "Отказ %", "След. t");
    private final DefaultTableModel buffer = model("Место", "Заявка", "В буфере с", "Указатель");
    private final DefaultTableModel devices = model("Прибор", "Статус", "Заявка", "Готов в", "Обраб.");

    private static DefaultTableModel model(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
    }

    private static String number(double value) { return Simulation.fmt(value); }
    private static String percent(double value) { return String.format(Locale.ROOT, "%.1f%%", value); }

    private static JPanel section(String name, DefaultTableModel model) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(name));
        JTable table = new JTable(model);
        table.setRowHeight(27);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);
        return panel;
    }

    public SmoApp() {
        super("СМО — вариант 17");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 460);
        setMinimumSize(new Dimension(860, 370));
        setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(root);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        JButton restartButton = new JButton("Сначала");
        JButton settingsButton = new JButton("Настройки…");
        controls.add(nextButton);
        controls.add(restartButton);
        controls.add(settingsButton);

        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 13f));
        statusLabel.setBorder(new EmptyBorder(5, 3, 6, 3));
        eventText.setEditable(false);
        eventText.setOpaque(false);
        eventText.setLineWrap(true);
        eventText.setWrapStyleWord(true);
        eventText.setFocusable(false);
        eventText.setFont(eventText.getFont().deriveFont(13f));
        eventText.setBorder(new EmptyBorder(5, 3, 5, 3));

        JPanel header = new JPanel(new BorderLayout(0, 3));
        header.add(controls, BorderLayout.NORTH);
        JPanel captions = new JPanel(new BorderLayout());
        captions.add(statusLabel, BorderLayout.NORTH);
        captions.add(eventText, BorderLayout.CENTER);
        header.add(captions, BorderLayout.CENTER);
        root.add(header, BorderLayout.NORTH);

        JPanel tables = new JPanel(new GridLayout(1, 3, 10, 0));
        tables.add(section("Источники", sources));
        tables.add(section("Буфер", buffer));
        tables.add(section("Приборы", devices));
        root.add(tables, BorderLayout.CENTER);

        JLabel hint = new JLabel("З — указатель записи; В — указатель выборки. Заявка 2.3 — третья от источника И2.");
        hint.setBorder(new EmptyBorder(3, 3, 0, 3));
        root.add(hint, BorderLayout.SOUTH);

        nextButton.addActionListener(e -> {
            if (simulation.step()) refresh();  // one press = strictly one step
        });
        restartButton.addActionListener(e -> restart());
        settingsButton.addActionListener(e -> showSettings());
        getRootPane().setDefaultButton(nextButton); // Enter = next step
        restart();
        setVisible(true);
    }

    private void restart() {
        simulation = new Simulation(config);
        refresh();
    }

    private void showSettings() {
        String[] names = {"Источников", "Приборов", "Мест в буфере", "Интенсивность одного источника (λ)",
                "Обслуживание: от", "Обслуживание: до", "Всего заявок", "Seed (для повторения опыта)"};
        String[] defaults = {"" + config.sources(), "" + config.devices(), "" + config.bufferSize(),
                "" + config.lambda(), "" + config.serviceMin(), "" + config.serviceMax(),
                "" + config.maxRequests(), "" + config.seed()};
        JTextField[] fields = new JTextField[names.length];
        JPanel settings = new JPanel(new GridLayout(names.length, 2, 8, 8));
        for (int i = 0; i < fields.length; i++) {
            settings.add(new JLabel(names[i]));
            fields[i] = new JTextField(defaults[i], 9);
            settings.add(fields[i]);
        }
        while (true) {
            int answer = JOptionPane.showConfirmDialog(this, settings, "Параметры моделирования",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (answer != JOptionPane.OK_OPTION) return;
            try {
                config = new Simulation.Config(
                        Integer.parseInt(fields[0].getText().trim()),
                        Integer.parseInt(fields[1].getText().trim()),
                        Integer.parseInt(fields[2].getText().trim()),
                        Double.parseDouble(fields[3].getText().trim().replace(',', '.')),
                        Double.parseDouble(fields[4].getText().trim().replace(',', '.')),
                        Double.parseDouble(fields[5].getText().trim().replace(',', '.')),
                        Integer.parseInt(fields[6].getText().trim()),
                        Long.parseLong(fields[7].getText().trim()));
                restart();
                return;
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(this, "Проверь параметры: " + ex.getMessage(),
                        "Неверные параметры", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void refresh() {
        Simulation s = simulation;
        Simulation.StatisticsCollector stats = s.getStatistics();
        statusLabel.setText(String.format(Locale.ROOT,
                "Шаг %d  |  Время %s  |  Сгенерировано %d  |  Обработано %d  |  Отказы %d (%.1f%%)%s",
                s.getStepNumber(), number(s.getTime()), stats.getTotalGenerated(),
                stats.getTotalCompleted(), stats.getTotalDropped(), stats.failurePercent(),
                s.isFinished() ? "  |  ЗАВЕРШЕНО" : ""));
        eventText.setText(s.getLastDescription());

        sources.setRowCount(0);
        for (Simulation.Source source : s.getSources()) {
            int id = source.getId();
            sources.addRow(new Object[] {"И" + id, stats.getGeneratedCount(id - 1),
                    stats.getDroppedCount(id - 1), percent(stats.failurePercent(id - 1)),
                    number(source.getNextGenerationTime())});
        }
        buffer.setRowCount(0);
        Simulation.RingBuffer ring = s.getBuffer();
        for (int index = 0; index < ring.capacity(); index++) {
            Simulation.Request request = ring.get(index);
            String pointers = (index == ring.getWritePointer() ? "З " : "")
                    + (index == ring.getReadPointer() ? "В" : "");
            buffer.addRow(new Object[] {"Б" + (index + 1), request == null ? "—" : request.label(),
                    request == null ? "—" : number(request.getBufferEntryTime()), pointers});
        }
        devices.setRowCount(0);
        for (Simulation.Device device : s.getManager().all()) {
            devices.addRow(new Object[] {"П" + device.getId(), device.isBusy() ? "Занят" : "Свободен",
                    device.isBusy() ? device.getCurrentRequest().label() : "—",
                    number(device.getFinishTime()), device.getServicedCount()});
        }
        nextButton.setEnabled(!s.isFinished());
    }

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) { }
        SwingUtilities.invokeLater(SmoApp::new);
    }
}
