// FILE: JavaClass.java

public class JavaClass {

    public Double minus0(){
        return -0.0;
    }

    public Double plus0(){
        return 0.0;
    }

    public Double null0(){
        return null;
    }

}


// FILE: b.kt

fun box(): String {
    val jClass = JavaClass()

    if (jClass.minus0() < jClass.plus0()) return "fail 1"

    //TODO: KT-14989
    //if (jClass.null0() < jClass.plus0()) return "fail 2"


    if (jClass.plus0() > jClass.minus0()) return "fail 3"

    //TODO: KT-14989
    //if (jClass.null0() < jClass.plus0()) return "fail 4"

    if (jClass.minus0() != jClass.plus0()) return "fail 5"

    //TODO: KT-14989
    //if (jClass.null0() != jClass.plus0()) return "fail 6"

    return "OK"
}

