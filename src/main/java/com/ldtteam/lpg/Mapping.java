package com.ldtteam.lpg;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.google.common.collect.Maps;
import org.checkerframework.checker.units.qual.C;

import java.util.Map;
import java.util.stream.Collectors;

public class Mapping
{
    private Map<String, String> mappings = Maps.newHashMap();

    public Mapping()
    {
    }

    public Mapping(Map<String, String> mappings)
    {
        this.mappings = mappings;
    }

    public Map<String, String> getMappings()
    {
        return mappings;
    }

    public void setMappings(final Map<String, String> mappings)
    {
        this.mappings = mappings;
    }

    public Type processTypeForMapping(Type toProcess)
    {
        if (!(toProcess.isClassOrInterfaceType()))
        {
            return toProcess;
        }

        final ClassOrInterfaceType classOrInterfaceType = toProcess.asClassOrInterfaceType();
        final String name = this.processTypeName(classOrInterfaceType);

        if (!mappings.containsKey(name))
        {
            return toProcess;
        }

        final ClassOrInterfaceType mappedType = new ClassOrInterfaceType(mappings.get(name));
        mappedType.setAnnotations(toProcess.getAnnotations());

        return mappedType;
    }

    private String processTypeName(ClassOrInterfaceType classOrInterfaceType)
    {
        String name = classOrInterfaceType.getNameAsString();
        if (classOrInterfaceType.getTypeArguments().isPresent())
        {
            final NodeList<Type> types = classOrInterfaceType.getTypeArguments().get();
            name += "<";
            name += types.stream().filter(type->type.isClassOrInterfaceType()).map(type -> processTypeName(type.asClassOrInterfaceType())).collect(Collectors.joining(", "));
            name += ">";
        }

        return name;
    }
}
