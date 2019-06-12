package com.ldtteam.lpg;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.VoidType;
import com.google.common.collect.Lists;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;

public class Main {

    private static Mapping mappings = null;

    public static void main(String[] args) throws FileNotFoundException
    {
        final File inputFile = new File(args[0]);
        final File mappingFile = new File(args[1]);

        final java.lang.reflect.Type mappingsTypeToken = new TypeToken<Map<String, String>>() {}.getType();
        final Mapping mapping = new Mapping(new GsonBuilder().create().fromJson(new InputStreamReader(new FileInputStream(mappingFile)), mappingsTypeToken));
        Main.mappings = mapping;

        final String[] validMethodNames = args.length >= 3 ? Arrays.copyOfRange(args, 2, args.length) : new String[]{"*"};
        CompilationUnit compilationUnit = new JavaParser().parse(inputFile).getResult().get();
        generateLogicBuilder(inputFile, compilationUnit, Lists.newArrayList(validMethodNames));
    }


    public static void generateLogicBuilder(final File inputFile, final CompilationUnit source, final List<String> validMethodNames)
    {
        new ArrayList<>(source.getChildNodes()).stream().filter(n -> n instanceof ClassOrInterfaceDeclaration).forEach(d -> {
            try
            {
                generateInterface(inputFile, source, generateCompileUnit(source.getPackageDeclaration().get().getNameAsString()), ((ClassOrInterfaceDeclaration) d), validMethodNames);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
            try
            {
                generateLogicBuilder(inputFile, source, generateCompileUnit(source.getPackageDeclaration().get().getNameAsString() + ".logic.builder"), (ClassOrInterfaceDeclaration) d, validMethodNames);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        });
    }

    public static void generateInterface(final File inputFile, final CompilationUnit source, final CompilationUnit target, final ClassOrInterfaceDeclaration sourceClass, final List<String> validMethodNames)
      throws IOException
    {
        target.addImport("com.ldtteam.jvoxelizer.core.logic.*");
        final ClassOrInterfaceDeclaration i = target.addInterface((sourceClass.isInterface() ?  "" : "I") + sourceClass.getNameAsString(), Modifier.Keyword.PUBLIC);

        i.addTypeParameter("I");

        if (sourceClass.getExtendedTypes().size() > 0)
        {
            final ClassOrInterfaceType extended = sourceClass.getExtendedTypes(0);
            i.addExtendedType( "I" + extended.getName() + "<I>");
        }
        else
        {
            i.addExtendedType("IInstancedObject<I>");
        }

        sourceClass.getImplementedTypes().forEach(i::addExtendedType);

        final List<MethodDeclaration> methodDeclarations = new ArrayList<>(sourceClass.getMethods());

        methodDeclarations.removeIf(m -> m.getModifiers().contains(Modifier.privateModifier()) || m.getModifiers().contains(Modifier.staticModifier()));
        methodDeclarations.removeIf(m -> m.getNameAsString().startsWith("set"));
        methodDeclarations.removeIf(m -> !validMethodNames.contains("*") && !validMethodNames.contains(m.getNameAsString()));

        methodDeclarations.forEach(m -> {
            final MethodDeclaration mNew = i.addMethod(m.getNameAsString()).setType(mappings.processTypeForMapping(m.getType())).setBody(null);

            m.getTypeParameters().forEach(mNew::addTypeParameter);
            m.getParameters().stream().map(Main::processMethodParameter).forEach(mNew::addParameter);
        });

        //TODO: Export
        final File parentDirectory = inputFile.getAbsoluteFile().getParentFile();
        final File targetFile = new File(parentDirectory.getAbsolutePath() + target.getPackageDeclaration().get().getNameAsString().replace(source.getPackageDeclaration().get().getNameAsString(), "").replace(".", "/").toLowerCase() + "/" + (sourceClass.isInterface() ?  "" : "I") + sourceClass.getNameAsString() + ".java");

        if (targetFile.exists())
            targetFile.delete();

        targetFile.getParentFile().mkdirs();
        targetFile.createNewFile();

        Files.write(targetFile.toPath(), target.toString().getBytes());
    }

    private static Parameter processMethodParameter(final Parameter parameter)
    {
        return new Parameter(mappings.processTypeForMapping(parameter.getType()), parameter.getName());
    }

    public static void generateLogicBuilder(final File inputFile, final CompilationUnit source, final CompilationUnit builderTarget, final ClassOrInterfaceDeclaration sourceClass, final List<String> validMethodNames)
      throws IOException
    {
        final ClassOrInterfaceDeclaration builder = builderTarget.addClass("Abstract" + (sourceClass.isInterface() ? sourceClass.getNameAsString().substring(1) :  sourceClass.getNameAsString()) + "Builder", Modifier.Keyword.PUBLIC, Modifier.Keyword.ABSTRACT);

        builder.addTypeParameter("C extends Abstract" + (sourceClass.isInterface() ? sourceClass.getNameAsString().substring(1) :  sourceClass.getNameAsString()) + "Builder<C, I, O>");
        builder.addTypeParameter("I");

        if (sourceClass.getExtendedTypes().size() > 0)
        {
            final ClassOrInterfaceType extended = sourceClass.getExtendedTypes(0);

            builder.addTypeParameter("O extends I" + extended.getNameAsString() + "<C, I, O>");
            builder.addExtendedType( "Abstract" + extended.getName() + "Builder<C, I, O>");
        }
        else
        {
            builder.addTypeParameter("O extends I" + sourceClass.getNameAsString() + "<I>");
        }

        final List<MethodDeclaration> methodDeclarations = new ArrayList<>(sourceClass.getMethods());
        final Map<String, MethodDeclaration> primaryMap = new HashMap<>();
        final Map<String, List<MethodDeclaration>> overloadMap = new HashMap<>();

        methodDeclarations.removeIf(m -> m.getModifiers().contains(Modifier.privateModifier()) || m.getModifiers().contains(Modifier.staticModifier()));
        methodDeclarations.removeIf(m -> m.getNameAsString().contains("set"));
        methodDeclarations.removeIf(m -> !validMethodNames.contains("*") && !validMethodNames.contains(m.getNameAsString()));

        methodDeclarations.sort((o1, o2) -> {
            final int sortByName = o1.getNameAsString().compareTo(o2.getNameAsString());

            if (sortByName != 0)
                return sortByName;

            final int sortByParameterCount = o1.getParameters().size() - o2.getParameters().size();
            if (sortByParameterCount != 0)
                return sortByParameterCount;

            for (int i = 0; i < o1.getParameters().size(); i++)
            {
                final Parameter p1 = processMethodParameter(o1.getParameter(i));
                final Parameter p2 = processMethodParameter(o2.getParameter(i));

                final int sortByParameterName = p1.getNameAsString().compareTo(p2.getNameAsString());
                if (sortByParameterName != 0)
                    return sortByParameterName;

                final int sortByParameterType = p1.getTypeAsString().compareTo(p2.getNameAsString());
                if (sortByParameterType != 0)
                    return sortByParameterType;
            }

            return 0;
        });

        methodDeclarations.forEach(m -> {
            primaryMap.putIfAbsent(m.getNameAsString(), m);
            overloadMap.putIfAbsent(m.getNameAsString(), new LinkedList<>());

            if (primaryMap.get(m.getNameAsString()) != m)
            {
                overloadMap.get(m.getNameAsString()).add(m);
            }
        });

        primaryMap.entrySet().forEach(e -> {
            try
            {
                generateLogicBuilderMethod(inputFile, source, builderTarget, builder, e.getValue(), e.getKey(), new ArrayList<>());
            }
            catch (IOException e1)
            {
                e1.printStackTrace();
            }

            for (int i = 0; i < overloadMap.get(e.getKey()).size(); i++)
            {
                final MethodDeclaration overload = overloadMap.get(e.getKey()).get(i);

                //Find the smallest overload for j < i;
                List<String> overloadData = generateOverloadData(e.getValue(), overload);
                for (int j = 0; j < overloadMap.get(e.getKey()).size() && j < i; j++)
                {
                    final MethodDeclaration otherOverload = overloadMap.get(e.getKey()).get(j);
                    final List<String> overloadDataCandidate = generateOverloadData(otherOverload, overload);

                    if (overloadDataCandidate.size() <= overloadData.size())
                    {
                        overloadData = overloadDataCandidate;
                    }
                }

                try
                {
                    generateLogicBuilderMethod(inputFile, source, builderTarget, builder, overload, e.getKey(), overloadData);
                }
                catch (IOException e1)
                {
                    e1.printStackTrace();
                }
            }
        });

        if (sourceClass.getExtendedTypes().size() == 0)
        {
            builder.addMethod("build", Modifier.Keyword.PUBLIC, Modifier.Keyword.ABSTRACT).setType("I" + sourceClass.getNameAsString() + "<I>").setBody(null).addParameter("I", "guiContext");
        }

        //TODO: Export:
        final File parentDirectory = inputFile.getAbsoluteFile().getParentFile();
        final File targetFile = new File(parentDirectory.getAbsolutePath() + builderTarget.getPackageDeclaration().get().getNameAsString().replace(source.getPackageDeclaration().get().getNameAsString(), "").replace(".", "/").toLowerCase() + "/Abstract" + (sourceClass.isInterface() ? sourceClass.getNameAsString().substring(1) :  sourceClass.getNameAsString()) + "Builder.java");

        if (targetFile.exists())
            targetFile.delete();

        targetFile.getParentFile().mkdirs();
        targetFile.createNewFile();

        Files.write(targetFile.toPath(), builderTarget.toString().getBytes());
    }

    private static List<String> generateOverloadData(
      MethodDeclaration primary,
      MethodDeclaration overload
    )
    {
        final List<String> overloadData = new ArrayList<>();

        for (int i = 0; i < overload.getParameters().size(); i++)
        {
            if (i < primary.getParameters().size())
            {
                final Parameter p1 = processMethodParameter(primary.getParameter(i));
                final Parameter p2 = processMethodParameter(overload.getParameter(i));

                if (!p1.getNameAsString().equals(p2.getNameAsString()) || !p1.getTypeAsString().equals(p2.getTypeAsString()))
                {
                    overloadData.add(Capitalize(p2.getNameAsString()) + "As" + Capitalize(getCleanedTypeName(p2.getType())));
                }
            }
            else
            {
                final Parameter p2 = processMethodParameter(overload.getParameter(i));
                overloadData.add(p2.getNameAsString() + "As" + getCleanedTypeName(p2.getType()));
            }
        }

        return overloadData;
    }

    public static void generateLogicBuilderMethod(
      final File inputFile,
      final CompilationUnit source,
      final CompilationUnit builderUnit,
      final ClassOrInterfaceDeclaration builder,
      final MethodDeclaration method,
      final String methodName,
      final List<String> overloadMethodVariableNames) throws IOException
    {
        final JavaParser parser = new JavaParser();

        String contextClassName = Capitalize(methodName);
        if (!overloadMethodVariableNames.isEmpty())
        {
            contextClassName += "With";
            for (int i = 0; i < overloadMethodVariableNames.size(); i++)
            {
                contextClassName += Capitalize(overloadMethodVariableNames.get(i));
                if (i != (overloadMethodVariableNames.size() - 1))
                    contextClassName += "And";
            }
        }
        final String methodNameWithOverloads = contextClassName;
        contextClassName += "Context";

        builderUnit.addImport(source.getPackageDeclaration().get().getNameAsString() + ".*");
        builderUnit.addImport(builderUnit.getPackageDeclaration().get().getNameAsString() + ".contexts.*");
        builderUnit.addImport("java.util.function.Function");
        builderUnit.addImport("java.util.function.Consumer");
        builderUnit.addImport("java.util.List");
        builderUnit.addImport("java.util.ArrayList");
        builderUnit.addImport("java.util.Arrays");
        builderUnit.addImport("com.ldtteam.jvoxelizer.core.logic.*");

        final MethodDeclaration logicMethod = builder.addMethod(methodNameWithOverloads, Modifier.Keyword.PUBLIC);
        logicMethod.setType("C");
        if (!method.getType().equals(new VoidType()))
        {
            //We have a return type.
            builder.addFieldWithInitializer(parser.parseClassOrInterfaceType(
              "List<Function<" + getTypedContextClassName(method, contextClassName) + ", " + (method.getTypeParameters()
                                                                                                .stream()
                                                                                                .anyMatch(t -> t.getNameAsString().equals(mappings.processTypeForMapping(method.getType()).asString()))
                                                                                                ? method.getTypeParameters()
                                                                                                    .stream()
                                                                                                    .filter(t -> t.getNameAsString().equals(mappings.processTypeForMapping(method.getType()).asString()))
                                                                                                    .map(t -> t.toString())
                                                                                                    .findFirst()
                                                                                                    .orElse("")
                                                                                                    .replace(
                                                                                                      mappings.processTypeForMapping(method.getType()).asString(), "?")
                                                                                                : Capitalize(mappings.processTypeForMapping(method.getType()).asString())) + ">>").getResult().get(), methodNameWithOverloads + "Pipeline", parser.parseExpression("new ArrayList<>()").getResult().get(), Modifier.Keyword.PRIVATE, Modifier.Keyword.FINAL);
            logicMethod.addAndGetParameter(parser.parseClassOrInterfaceType("Function<"+ getTypedContextClassName(method, contextClassName)+", " + Capitalize(mappings.processTypeForMapping(method.getType()).asString()) + ">").getResult().get(), "components").setVarArgs(true);

            if (method.getTypeParameters().size() > 0)
            {
                method.getTypeParameters().forEach(logicMethod::addTypeParameter);
            }
        }
        else
        {
            builder.addFieldWithInitializer(parser.parseClassOrInterfaceType("List<Consumer<" + getVoidContextClassName(method, contextClassName) + ">>").getResult().get(), methodNameWithOverloads + "Pipeline", parser.parseExpression("new ArrayList<>()").getResult().get(), Modifier.Keyword.PRIVATE, Modifier.Keyword.FINAL);
            logicMethod.addAndGetParameter(parser.parseClassOrInterfaceType("Consumer<" + getVoidContextClassName(method, contextClassName) + ">").getResult().get(), "components").setVarArgs(true);
        }
        logicMethod.setBody(parser.parseBlock("{ \n"
                                                + "   this." + methodNameWithOverloads + "Pipeline.addAll(Arrays.asList(components));\n"
                                                + "   return (C) this;\n"
                                                + "}").getResult().get());

        final CompilationUnit contextTarget = generateCompileUnit(builderUnit.getPackageDeclaration().get().getNameAsString()+".contexts");

        final ClassOrInterfaceDeclaration contextClass = contextTarget.addClass(contextClassName, Modifier.Keyword.PUBLIC);

        method.getTypeParameters().forEach(contextClass::addTypeParameter);

        final ConstructorDeclaration constructorDeclaration = contextClass.addConstructor(Modifier.Keyword.PUBLIC);
        constructorDeclaration.setBody(new BlockStmt());

        method.getParameters().stream().map(Main::processMethodParameter).forEach(p -> {
            constructorDeclaration.addParameter(p.getType(), (p.getNameAsString().startsWith("I") ? p.getNameAsString().substring(1) : p.getNameAsString()));
            constructorDeclaration.getBody().addStatement(new ExpressionStmt(new AssignExpr(new FieldAccessExpr(new ThisExpr(), (p.getNameAsString().startsWith("I") ? p.getNameAsString().substring(1) : p.getNameAsString())), new NameExpr((p.getNameAsString().startsWith("I") ? p.getNameAsString().substring(1) : p.getNameAsString())), AssignExpr.Operator.ASSIGN)));

            //Generate field, getter and setter.
            final FieldDeclaration backingField = contextClass.addField(p.getType(), (p.getNameAsString().startsWith("I") ? p.getNameAsString().substring(1) : p.getNameAsString()), Modifier.Keyword.PRIVATE);
            final MethodDeclaration getterMethod = contextClass.addMethod("get" + Capitalize((p.getNameAsString().startsWith("I") ? p.getNameAsString().substring(1) : p.getNameAsString())), Modifier.Keyword.PUBLIC).setType(p.getType());
            getterMethod.setBody(new BlockStmt(new NodeList<>(new ReturnStmt(new NameExpr((p.getNameAsString().startsWith("I") ? p.getNameAsString().substring(1) : p.getNameAsString()))))));

            final MethodDeclaration setterMethod = contextClass.addMethod("set" + Capitalize((p.getNameAsString().startsWith("I") ? p.getNameAsString().substring(1) : p.getNameAsString())), Modifier.Keyword.PUBLIC);
            setterMethod.addParameter(p.getType(), (p.getNameAsString().startsWith("I") ? p.getNameAsString().substring(1) : p.getNameAsString()));
            setterMethod.setBody(new BlockStmt(new NodeList<>(new ExpressionStmt(new AssignExpr(new FieldAccessExpr(new ThisExpr(), (p.getNameAsString().startsWith("I") ? p.getNameAsString().substring(1) : p.getNameAsString())), new NameExpr((p.getNameAsString().startsWith("I") ? p.getNameAsString().substring(1) : p.getNameAsString())), AssignExpr.Operator.ASSIGN)))));
        });

        //TODO: Export:
        final File parentDirectory = inputFile.getAbsoluteFile().getParentFile();
        final File targetFile = new File(parentDirectory.getAbsolutePath() + contextTarget.getPackageDeclaration().get().getNameAsString().replace(source.getPackageDeclaration().get().getNameAsString(), "").replace(".", "/").toLowerCase() + "/" + contextClassName + ".java");

        if (targetFile.exists())
            targetFile.delete();

        targetFile.getParentFile().mkdirs();
        targetFile.createNewFile();

        Files.write(targetFile.toPath(), contextTarget.toString().getBytes());
    }

    private static String getVoidContextClassName(final MethodDeclaration method, final String contextClassName)
    {
        return "VoidPipelineElementContext<" + getFullContextClassName(method, contextClassName) + ", O, I>";
    }

    private static String getTypedContextClassName(final MethodDeclaration method, final String contextClassName)
    {
        return "TypedPipelineElementContext<" + getFullContextClassName(method, contextClassName)+", " + (method.getTypeParameters().size() > 0 ? method.getTypeParameters()
                                                                                                                                                          .stream()
                                                                                                                                                          .map(p -> p.toString().replace(p.getNameAsString(), "?"))
                                                                                                                                                          .collect(
                                                                                                                                                            Collectors.joining(", ")) : Capitalize(mappings.processTypeForMapping(method.getType()).asString())) + ", O, I>";
    }

    private static  String getShortContextClassName(final MethodDeclaration method, final String contextClassName)
    {
        return contextClassName + (method.getTypeParameters().size() > 0 ? "<>": "");
    }

    private static String getFullContextClassName(final MethodDeclaration method, final String contextClassName)
    {
        return contextClassName + (method.getTypeParameters().size() > 0 ? "<" + method.getTypeParameters()
                                                                                   .stream()
                                                                                   .map(p -> p.toString().replace(p.getNameAsString(), "?"))
                                                                                   .collect(
                                                                                     Collectors.joining(", ")) + ">" : "");
    }

    private static String getCleanedTypeName(final Type type)
    {
        if (type.isClassOrInterfaceType())
        {
            final ClassOrInterfaceType c = type.asClassOrInterfaceType();

            if (c.getTypeArguments().isPresent())
            {
                return c.getTypeArguments().get().stream().map(t -> getCleanedTypeName(t)).collect(Collectors.joining()) + c.getNameAsString();
            }

            return c.asString();
        }

        return type.asString();
    }

    private static CompilationUnit generateCompileUnit(String packageName)
    {
        CompilationUnit cu = new CompilationUnit();
        cu.setPackageDeclaration(packageName);

        return cu;
    }

    private static String Capitalize(String string)
    {
        return string.substring(0,1).toUpperCase() + (string.length() > 1 ? string.substring(1) : "");
    }
}
