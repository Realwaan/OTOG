import javax.swing.*;

public class UserLogin extends JFrame{
    private JPanel contentPane;
    private JTextField userNameTextField;
    private JPasswordField passwordTextField;
    private JButton loginButton;


    public static void main(String[] args) {
        UserLogin app = new UserLogin();

        JFrame frame = new JFrame("User Login");
        frame.setContentPane(app.contentPane);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(300, 280);
        frame.setLocationRelativeTo(null);
        frame.setResizable(false);
        frame.setVisible(true);

    }
}



