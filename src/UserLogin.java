import javax.swing.*;

public class UserLogin extends JFrame {
    private JPanel pnlMain;
    private JTextField textFieldUsername;
    private JPasswordField passwordFieldPasswordInput;
    private JButton btnLogin;
    private JPanel pnlPassword;
    private JPanel pnl2FA;
    private JTextField textField2FA;
    private JButton btnVerify2FA;

    public UserLogin() {
        // Ensure initial state: login panel visible, 2FA panel hidden
        pnlPassword.setVisible(true);
        pnl2FA.setVisible(false);

        btnLogin.addActionListener(e -> {
            String username = textFieldUsername.getText();
            String password = new String(passwordFieldPasswordInput.getPassword());

            if (username.equals("admin") && password.equals("password")) {
                // Hide the login panel and show the 2FA panel
                pnlPassword.setVisible(false);
                pnl2FA.setVisible(true);

                // Refresh the main panel layout
                pnlMain.revalidate();
                pnlMain.repaint();
            } else {
                // Show error popup for wrong credentials
                JOptionPane.showMessageDialog(
                        pnlMain,
                        "Wrong credentials! Please try again.",
                        "Login Error",
                        JOptionPane.ERROR_MESSAGE
                );
                passwordFieldPasswordInput.setText("");
            }
        });

        // Optional: Action listener for the 2FA Verify button
        if (btnVerify2FA != null) {
            btnVerify2FA.addActionListener(e -> {
                String code = textField2FA.getText().trim();
                if (!code.isEmpty()) {
                    JOptionPane.showMessageDialog(
                            pnlMain,
                            "Two-Factor Authentication successful! Welcome, admin.",
                            "Success",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                } else {
                    JOptionPane.showMessageDialog(
                            pnlMain,
                            "Please enter your 2FA verification code.",
                            "2FA Error",
                            JOptionPane.WARNING_MESSAGE
                    );
                }
            });
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            UserLogin app = new UserLogin();

            JFrame frame = new JFrame("User Login");
            frame.setContentPane(app.pnlMain);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(750, 380);
            frame.setLocationRelativeTo(null);
            frame.setResizable(false);
            frame.setVisible(true);
        });
    }
}