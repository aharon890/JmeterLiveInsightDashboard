package com.jmeterinsight.livedashboard.plugin;

import org.apache.jmeter.gui.util.VerticalPanel;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.visualizers.gui.AbstractListenerGui;

import javax.swing.*;
import java.awt.*;

/**
 * JMeter GUI panel for configuring the Live Dashboard Listener.
 * Appears under: Test Plan → Add → Listener → Live Insight Dashboard
 */
public class LiveDashboardListenerGui extends AbstractListenerGui {

    private JTextField portField;
    private JComboBox<String> bindAddressCombo;
    private JCheckBox openBrowserCheckbox;
    private JTextField updateIntervalField;
    private JTextField maxRetentionField;
    private JTextField hidePatternsField;

    public LiveDashboardListenerGui() {
        init();
    }

    private void init() {
        setLayout(new BorderLayout());
        setBorder(makeBorder());

        add(makeTitlePanel(), BorderLayout.NORTH);
        add(createConfigPanel(), BorderLayout.CENTER);
    }

    private JPanel createConfigPanel() {
        VerticalPanel mainPanel = new VerticalPanel();

        // Server Settings
        JPanel serverPanel = createTitledPanel("Server Settings");
        portField = new JTextField(String.valueOf(LiveDashboardListener.DEFAULT_PORT), 10);
        portField.setToolTipText("HTTP port for the dashboard. If in use, auto-increments up to +10.");
        addField(serverPanel, "Port:", portField);

        // Editable, with the two common choices as suggestions: the server binds any
        // address, and a fixed list could not display one it did not hold — JMeter then
        // saved its selection over a plan's hand-edited interface address.
        bindAddressCombo = new JComboBox<>(new String[]{"localhost", "0.0.0.0"});
        bindAddressCombo.setEditable(true);
        bindAddressCombo.setToolTipText("localhost = this machine only. 0.0.0.0 = accessible from other machines on the network. "
                + "Or type the address of one network interface.");
        addField(serverPanel, "Bind Address:", bindAddressCombo);

        openBrowserCheckbox = new JCheckBox("Auto-open browser on test start", true);
        openBrowserCheckbox.setToolTipText("Automatically open the dashboard in your default browser when the test starts.");
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = getNextRow(serverPanel);
        gbc.gridwidth = 2; gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(2, 5, 2, 5);
        serverPanel.add(openBrowserCheckbox, gbc);
        mainPanel.add(serverPanel);

        // Update Settings
        JPanel updatePanel = createTitledPanel("Update Settings");
        updateIntervalField = new JTextField("2", 10);
        updateIntervalField.setToolTipText("How often the server pushes new data to the dashboard (seconds). Lower = more responsive but more CPU. Default: 2s.");
        addField(updatePanel, "Update Interval (seconds):", updateIntervalField);

        maxRetentionField = new JTextField("60", 10);
        maxRetentionField.setToolTipText("How long chart history is kept in server memory. Older data is pruned. Does not affect summary statistics. Default: 60 min.");
        addField(updatePanel, "Chart History Limit (minutes):", maxRetentionField);
        mainPanel.add(updatePanel);

        // Sampler Filtering
        JPanel filterPanel = createTitledPanel("Sampler Filtering");
        hidePatternsField = new JTextField(LiveDashboardListener.DEFAULT_HIDE_PATTERNS_STR, 30);
        hidePatternsField.setToolTipText("Comma-separated patterns (wildcards allowed) for samplers to hide by default. E.g. JSR223*,Debug*");
        addField(filterPanel, "Auto-hide patterns:", hidePatternsField);
        JLabel filterHelp = new JLabel("Comma-separated, wildcards: * (any chars), ? (one char). E.g. JSR223*,Debug*,*setup*");
        filterHelp.setFont(filterHelp.getFont().deriveFont(Font.ITALIC, 11f));
        filterHelp.setForeground(Color.GRAY);
        GridBagConstraints helpGbc = new GridBagConstraints();
        helpGbc.gridx = 1; helpGbc.gridy = getNextRow(filterPanel);
        helpGbc.anchor = GridBagConstraints.WEST;
        helpGbc.insets = new Insets(0, 5, 4, 5);
        filterPanel.add(filterHelp, helpGbc);
        mainPanel.add(filterPanel);


        return mainPanel;
    }

    private JPanel createTitledPanel(String title) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(title));
        return panel;
    }

    private int getNextRow(JPanel panel) {
        // Count existing components to determine next row
        return panel.getComponentCount() / 2;
    }

    private void addField(JPanel panel, String label, JComponent field) {
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 5, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;

        int row = getNextRow(panel);

        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label), gbc);

        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(field, gbc);
    }

    @Override
    public String getStaticLabel() {
        return "Live Insight Dashboard";
    }

    @Override
    public String getLabelResource() {
        return "live_insight_dashboard";
    }

    @Override
    public TestElement createTestElement() {
        LiveDashboardListener listener = new LiveDashboardListener();
        modifyTestElement(listener);
        return listener;
    }

    @Override
    public void modifyTestElement(TestElement element) {
        super.configureTestElement(element);

        element.setProperty(LiveDashboardListener.PORT, portField.getText().trim());
        // The editor's text, not getSelectedItem(): typed text is committed only on
        // Enter or focus loss, and a click on the tree can reach here first.
        Object bind = bindAddressCombo.getEditor().getItem();
        element.setProperty(LiveDashboardListener.BIND_ADDRESS, bind == null ? "" : bind.toString().trim());
        element.setProperty(LiveDashboardListener.OPEN_BROWSER,
                String.valueOf(openBrowserCheckbox.isSelected()));

        try {
            // Clamp, and WRITE the clamped value. A field of 0 used to serialize to 0ms,
            // which DashboardServer's scheduler rejects after its socket is already bound,
            // leaking a server that nothing can stop. Skipping the write instead would
            // leave the element's previous value in place — a silent no-op.
            int intervalSec = Math.max(1, Integer.parseInt(updateIntervalField.getText().trim()));
            element.setProperty(LiveDashboardListener.UPDATE_INTERVAL, String.valueOf(intervalSec));
        } catch (NumberFormatException ignored) {}

        try {
            // Clamped and written for the same reason: 0 or below prunes every chart
            // slot the moment it closes.
            int retentionMin = Math.max(1, Integer.parseInt(maxRetentionField.getText().trim()));
            element.setProperty(LiveDashboardListener.MAX_RETENTION,
                    String.valueOf(retentionMin * 60));
        } catch (NumberFormatException ignored) {}

        element.setProperty(LiveDashboardListener.HIDE_PATTERNS, hidePatternsField.getText().trim());
    }

    @Override
    public void configure(TestElement element) {
        super.configure(element);

        portField.setText(element.getPropertyAsString(LiveDashboardListener.PORT,
                String.valueOf(LiveDashboardListener.DEFAULT_PORT)));
        bindAddressCombo.setSelectedItem(element.getPropertyAsString(
                LiveDashboardListener.BIND_ADDRESS, LiveDashboardListener.DEFAULT_BIND));
        openBrowserCheckbox.setSelected(
                Boolean.parseBoolean(element.getPropertyAsString(
                        LiveDashboardListener.OPEN_BROWSER, "true")));

        String interval = element.getPropertyAsString(LiveDashboardListener.UPDATE_INTERVAL,
                String.valueOf(LiveDashboardListener.DEFAULT_UPDATE_INTERVAL_SECONDS));
        try {
            int value = Integer.parseInt(interval);
            // A plan saved by an older version holds milliseconds (2000); shown, and so
            // saved back, in seconds. See LiveDashboardListener.getUpdateIntervalConfig.
            if (value >= LiveDashboardListener.LEGACY_MS_INTERVAL_FROM) {
                value = Math.max(1, Math.round(value / 1000f));
            }
            updateIntervalField.setText(String.valueOf(value));
        } catch (NumberFormatException e) {
            updateIntervalField.setText("2");
        }

        String retentionSec = element.getPropertyAsString(LiveDashboardListener.MAX_RETENTION,
                String.valueOf(LiveDashboardListener.DEFAULT_MAX_RETENTION_SECONDS));
        try {
            maxRetentionField.setText(String.valueOf(Integer.parseInt(retentionSec) / 60));
        } catch (NumberFormatException e) {
            maxRetentionField.setText("60");
        }

        hidePatternsField.setText(element.getPropertyAsString(
                LiveDashboardListener.HIDE_PATTERNS, LiveDashboardListener.DEFAULT_HIDE_PATTERNS_STR));
    }

    @Override
    public void clearGui() {
        super.clearGui();
        portField.setText(String.valueOf(LiveDashboardListener.DEFAULT_PORT));
        bindAddressCombo.setSelectedItem(LiveDashboardListener.DEFAULT_BIND);
        openBrowserCheckbox.setSelected(true);
        updateIntervalField.setText("2");
        maxRetentionField.setText("60");
        hidePatternsField.setText(LiveDashboardListener.DEFAULT_HIDE_PATTERNS_STR);
    }
}
