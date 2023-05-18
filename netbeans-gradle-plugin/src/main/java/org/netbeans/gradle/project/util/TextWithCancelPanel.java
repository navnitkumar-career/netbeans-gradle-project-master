package org.netbeans.gradle.project.util;

import java.util.Objects;
import javax.swing.JButton;
import javax.swing.JLabel;
import org.jtrim2.cancel.CancellationController;
import org.netbeans.gradle.project.NbStrings;

@SuppressWarnings("serial")
class TextWithCancelPanel extends javax.swing.JPanel {
    private final CancellationController cancelTask;

    public TextWithCancelPanel(String text, CancellationController cancelTask) {
        Objects.requireNonNull(text, "text");

        this.cancelTask = cancelTask;

        initComponents();

        jPanelText.setText(text);
        jCancelButton.setText(NbStrings.getCancelOption());

        if (cancelTask == null) {
            jCancelButton.setVisible(false);
        }
    }

    public JLabel getPanelTextComponent() {
        return jPanelText;
    }

    public JButton getCancelButton() {
        return jCancelButton;
    }

    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings({"unchecked", "Convert2Lambda"})
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jPanelText = new javax.swing.JLabel();
        jCancelButton = new javax.swing.JButton();

        jPanelText.setText("PanelText");

        jCancelButton.setText("Cancel");
        jCancelButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jCancelButtonActionPerformed(evt);
            }
        });

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
        this.setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jPanelText, javax.swing.GroupLayout.DEFAULT_SIZE, 73, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(jCancelButton)
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jCancelButton)
                    .addComponent(jPanelText))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );
    }// </editor-fold>//GEN-END:initComponents

    private void jCancelButtonActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jCancelButtonActionPerformed
        cancelTask.cancel();
    }//GEN-LAST:event_jCancelButtonActionPerformed


    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton jCancelButton;
    private javax.swing.JLabel jPanelText;
    // End of variables declaration//GEN-END:variables
}
