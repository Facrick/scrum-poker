import java.util.Scanner;

public class CalculatorApp {
    public static void main(String[] args) {
        Scanner input = new Scanner(System.in);

        String resultText = "Результат: ";


        while (true) {
            calcMenu();

            int operation = input.nextInt();

            if (operation == 0) {
            System.out.println("Программа завершена");
            break;
            }

            if (operation < 1 || operation > 5) {
                System.out.println("Неизвестная операция");
                continue;
            }

            System.out.println("Введите первое число: ");
            double firstNumber = input.nextDouble();

            System.out.println("Введите второе число: ");
            double secondNumber = input.nextDouble();

            double result;

            switch (operation) {
                case 1:
                    result = add(firstNumber, secondNumber);
                    System.out.println(resultText + result);
                    break;
                case 2:
                    result = sub(firstNumber, secondNumber);
                    System.out.println(resultText + result);
                    break;
                case 3:
                    result = mul(firstNumber, secondNumber);
                    System.out.println(resultText + result);
                    break;
                case 4:
                    result = div(firstNumber, secondNumber);
                    System.out.println(resultText + result);
                    break;
                case 5:
                    result = modulo(firstNumber, secondNumber);
                    System.out.println(resultText + result);
                    break;
                default:
                    System.out.println("Неизвестная операция");
            }
        }

        input.close();

    }

    static void calcMenu() {
            System.out.println("=== Calculator App ===");
            System.out.println("1. Сложение");
            System.out.println("2. Вычитание");
            System.out.println("3. Умножение");
            System.out.println("4. Деление");
            System.out.println("5. Остаток от деления");
            System.out.println("6. Показать историю");
            System.out.println("0. Выход");
            System.out.println("Выберите операцию: ");
    }

    static double add(double a, double b) {
        return a + b;
    }

    static double sub(double a, double b) {
        return a - b;
    }

    static double mul(double a, double b) {
        return a * b;
    }

    static double div(double a, double b) {
        return a / b;
    }

    static double modulo(double a, double b) {
        return a % b;
    }

}
