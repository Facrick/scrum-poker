package org.example;

public class ReferencesDemo {
    public static void main(String[] args) {
        String first = "Java";
        String second = "Java";
        String third = new String("Java");

        System.out.println(first == second); //true Потому что сверяется ссылка на один и тот же объект
        System.out.println(first == third); //false Потому что сверяется ссылка одного объяекта с новым обЬектом
        System.out.println(first.equals(third)); //true В данном случае сверяются значения переменных String

        Integer a = 100;
        Integer b = 100;

        Integer c = 200;
        Integer d = 200;

        System.out.println(a == b); //true Сверяется число а и б
        System.out.println(c == d); //false Не могу ответить на этот вопрос
        System.out.println(c.equals(d)); //true потому что сверяет значение переменных
    }
}
