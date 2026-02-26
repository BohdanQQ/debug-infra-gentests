package cz.cuni.mff.d3s.autodebugger.intellijplugin.dialogs;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import cz.cuni.mff.d3s.autodebugger.intellijplugin.services.SettingsService;
import cz.cuni.mff.d3s.autodebugger.runner.config.ConfigurationField;
import cz.cuni.mff.d3s.autodebugger.runner.config.ConfigurationProvider;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings dialog for configuring auto-debugger options.
 * This dialog dynamically creates UI components based on configuration fields
 * provided by the runner module.
 */
public class SettingsDialog extends DialogWrapper {
    private static final Logger LOG = Logger.getInstance(SettingsDialog.class);

    private final Project project;
    private final SettingsService settingsService;
    private final Map<String, JComponent> fieldComponents = new HashMap<>();
    private JPanel contentPanel;

    public SettingsDialog(Project project) {
        super(project);
        this.project = project;
        this.settingsService = SettingsService.getInstance(project);
        
        setTitle("Auto-Debugger Settings");
        setResizable(true);
        init();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        contentPanel = new JPanel(new BorderLayout());
        
        // Create form builder
        FormBuilder formBuilder = FormBuilder.createFormBuilder();
        
        // Add required fields section
        List<ConfigurationField> requiredFields = ConfigurationProvider.getRequiredConfigurationFields();
        if (!requiredFields.isEmpty()) {
            formBuilder.addComponent(createSectionLabel("Required Configuration"));
            for (ConfigurationField field : requiredFields) {
                addFieldToForm(formBuilder, field);
            }
        }
        
        // Add optional fields section
        List<ConfigurationField> optionalFields = ConfigurationProvider.getOptionalConfigurationFields();
        if (!optionalFields.isEmpty()) {
            formBuilder.addVerticalGap(15);
            formBuilder.addComponent(createSectionLabel("Optional Configuration"));
            for (ConfigurationField field : optionalFields) {
                addFieldToForm(formBuilder, field);
            }
        }
        
        JPanel formPanel = formBuilder.getPanel();
        formPanel.setBorder(JBUI.Borders.empty(10));
        
        contentPanel.add(formPanel, BorderLayout.CENTER);
        contentPanel.setPreferredSize(new Dimension(500, 400));
        
        return contentPanel;
    }

    private JBLabel createSectionLabel(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD, label.getFont().getSize() + 1));
        return label;
    }

    private void addFieldToForm(FormBuilder formBuilder, ConfigurationField field) {
        JComponent component = createFieldComponent(field);
        fieldComponents.put(field.getKey(), component);
        
        String labelText = field.getDisplayName();
        if (field.isRequired()) {
            labelText += " *";
        }
        
        formBuilder.addLabeledComponent(labelText + ":", component);
        
        // Add description if available
        if (field.getDescription() != null && !field.getDescription().isEmpty()) {
            JBLabel descLabel = new JBLabel("<html><i>" + field.getDescription() + "</i></html>");
            descLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            formBuilder.addComponentToRightColumn(descLabel);
        }
        
        formBuilder.addVerticalGap(5);
    }

    private JComponent createFieldComponent(ConfigurationField field) {
        String currentValue = settingsService.getConfigurationValue(field.getKey());
        if (currentValue == null) {
            currentValue = field.getDefaultValue() != null ? field.getDefaultValue() : "";
        }

        switch (field.getType()) {
            case TEXT:
                JBTextField textField = new JBTextField(currentValue);
                return textField;
                
            case PASSWORD:
                JBPasswordField passwordField = new JBPasswordField();
                passwordField.setText(currentValue);
                return passwordField;
                
            case FILE_PATH:
                TextFieldWithBrowseButton fileField = new TextFieldWithBrowseButton();
                fileField.setText(currentValue);
                FileChooserDescriptor fileDescriptor = new FileChooserDescriptor(true, false, false, false, false, false);
                fileDescriptor.setTitle("Select " + field.getDisplayName());
                fileField.addBrowseFolderListener(field.getDisplayName(), field.getDescription(), project, fileDescriptor);
                return fileField;

            case DIRECTORY_PATH:
                TextFieldWithBrowseButton dirField = new TextFieldWithBrowseButton();
                dirField.setText(currentValue);
                FileChooserDescriptor dirDescriptor = new FileChooserDescriptor(false, true, false, false, false, false);
                dirDescriptor.setTitle("Select " + field.getDisplayName());
                dirField.addBrowseFolderListener(field.getDisplayName(), field.getDescription(), project, dirDescriptor);
                return dirField;
                
            case NUMBER:
                JBTextField numberField = new JBTextField(currentValue);
                // Add input validation for numbers if needed
                return numberField;
                
            case BOOLEAN:
                JBCheckBox checkBox = new JBCheckBox();
                checkBox.setSelected(Boolean.parseBoolean(currentValue));
                return checkBox;
                
            default:
                LOG.warn("Unknown field type: " + field.getType());
                return new JBTextField(currentValue);
        }
    }

    @Override
    protected void doOKAction() {
        // Validate and save settings
        Map<String, String> values = new HashMap<>();
        
        for (ConfigurationField field : ConfigurationProvider.getAllConfigurationFields()) {
            JComponent component = fieldComponents.get(field.getKey());
            String value = getValueFromComponent(component, field.getType());
            
            // Validate required fields
            if (field.isRequired() && (value == null || value.trim().isEmpty())) {
                setErrorText("Field '" + field.getDisplayName() + "' is required.");
                return;
            }
            
            values.put(field.getKey(), value != null ? value : "");
        }
        
        // Save all values
        settingsService.setConfigurationValues(values);
        
        super.doOKAction();
    }

    private String getValueFromComponent(JComponent component, ConfigurationField.FieldType type) {
        return switch (type) {
            case TEXT, NUMBER -> ((JBTextField) component).getText();
            case PASSWORD -> new String(((JBPasswordField) component).getPassword());
            case FILE_PATH, DIRECTORY_PATH -> ((TextFieldWithBrowseButton) component).getText();
            case BOOLEAN -> String.valueOf(((JBCheckBox) component).isSelected());
            default -> "";
        };
    }
}
