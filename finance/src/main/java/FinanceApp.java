import java.util.ArrayList;
import java.util.Scanner;


public class FinanceApp {
    public static void main(String[] args) {
        Scanner input = new Scanner(System.in);

        double balance = 0;

        boolean isRunning = true;

        while (isRunning) {
            //Показ меню.
            printMenu();

            // Сохраняем переменную полученную из метода inputMenu
            int choice = inputMenu(input);

            //Создаем переменную для суммы доходов/расходов
            double money = 0;

            //Создаем переключатель для каждого пункта меню
            switch (choice) {
                case 1:
                    System.out.print("\nУкажите сумму доходов: ");
                    money = input.nextDouble();

                    if (checkMoney(money)) {
                        sendDifferentSumMessage();
                        break;
                    }


                    balance = addMoney(balance, money);
                    System.out.println("\nТекущий баланс: " + balance + "\n");
                    break;

                case 2:
                    System.out.print("\nУкажите сумму расходов: ");
                    money = input.nextDouble();

                    if (checkMoney(money)) {
                        sendDifferentSumMessage();
                        break;
                    }

                    balance = subtractMoney(balance, money);
                    System.out.println("\nТекущий баланс: " + balance + "\n");
                    break;

                case 3:
                    System.out.println("\nТекущий баланс: " + balance + "\n");
                    break;

                case 4:
                    System.out.println("\nИстория операций пока не реализована\n");
                    break;

                case 5:
                    isRunning = false;
                    break;

                default:
                    System.out.println("\nНеверный пункт");

            }

        }

    }

    public static void printMenu() {
        System.out.println("=== Finance Tracker ===\n\n" +
                "1. Добавить доход\n" +
                "2. Добавить расход\n" +
                "3. Показать баланс\n" +
                "4. Показать историю операций\n" +
                "5. Выйти\n");
    }

    public static int inputMenu(Scanner input) {
        System.out.print("Выберите действие: ");
        return input.nextInt();
    }

    public static double addMoney(double balance, double money) {
        balance = balance + money;
        return balance;
    }

    public static double subtractMoney(double amount, double money) {
        amount = amount - money;
        return amount;
    }

    public static void sendDifferentSumMessage() {
        System.out.println("\nУкажите другую сумму");
    }

    public static boolean checkMoney(double money) {
        return money < 0 || money == 0;
    }

}
