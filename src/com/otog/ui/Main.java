package com.otog.ui;

import com.otog.model.User;
import com.otog.model.Wallet;
import com.otog.service.SessionManager;

import javax.swing.*;
import java.awt.*;

public class Main extends JFrame {

    public Main() {
        SessionManager.getInstance().requireAuthentication();

        User user = SessionManager.getInstance().getCurrentUser();
        Wallet wallet = SessionManager.getInstance().getCurrentWallet();

        String displayName = (user != null && user.getName() != null) ? user.getName() : "User";
        String balance = (wallet != null && wallet.getBalance() != null)
                ? "PHP " + wallet.getBalance().toPlainString()
                : "PHP 0.00";

        setTitle("OTOG E-Wallet — Dashboard");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(800, 500);
        setLocationRelativeTo(null);
        setResizable(false);

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

        JPanel header = new JPanel(new BorderLayout());
        JLabel welcomeLabel = new JLabel("Welcome, " + displayName + "   |   Balance: " + balance);
        welcomeLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        header.add(welcomeLabel, BorderLayout.WEST);

        JButton btnLogout = new JButton("Logout");
        btnLogout.addActionListener(e -> handleLogout());
        header.add(btnLogout, BorderLayout.EAST);

        root.add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Send Money", makePlaceholder("Send Money — coming soon"));
        tabs.addTab("Cash In / Out", makePlaceholder("Cash In / Out — coming soon"));
        tabs.addTab("Loans", makePlaceholder("Loans — coming soon"));
        tabs.addTab("Investments", makePlaceholder("Investments — coming soon"));

        root.add(tabs, BorderLayout.CENTER);
        setContentPane(root);
    }

    private JPanel makePlaceholder(String text) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(new Font("SansSerif", Font.PLAIN, 13));
        panel.add(label, BorderLayout.CENTER);
        return panel;
    }

    private Runnable onLogoutCallback;

    public void setOnLogoutCallback(Runnable callback) {
        this.onLogoutCallback = callback;
    }

    private void handleLogout() {
        SessionManager.getInstance().logout();
        dispose();
        if (onLogoutCallback != null) {
            SwingUtilities.invokeLater(onLogoutCallback);
        }
    }
}
