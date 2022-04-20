package org.metersphere.utils;

import com.intellij.lang.jvm.types.JvmSubstitutor;
import com.intellij.lang.jvm.types.JvmTypeResolveResult;
import com.intellij.psi.PsiJvmSubstitutor;
import com.intellij.psi.PsiSubstitutor;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.source.PsiClassReferenceType;

import java.util.HashMap;
import java.util.Map;

/**
 * PsiType工具类
 *
 * @author JustryDeng
 * @since 2021/12/18 10:18:04
 */
public final class PsiTypeUtil {

    private PsiTypeUtil() {
        throw new UnsupportedOperationException("Un-support to create instance");
    }

    /**
     * 解析指定PsiType中的泛型及实际使用时该泛型对应使用的类
     *
     * @param psiType
     *            待解析的类
     * @return  k-泛型占位符， v-使用时，泛型实际代表的类
     */
    @SuppressWarnings({"MissingRecentApi", "UnstableApiUsage"})
    public static Map<String, PsiType> parseGenericNameAndSubstitutorTypeMapping(PsiType psiType) {
        Map<String, PsiType> substitutorTypeMap  = new HashMap<>(4);
        if (psiType == null) {
            return substitutorTypeMap;
        }
        if (!(psiType instanceof PsiClassReferenceType)) {
            return substitutorTypeMap;
        }
        JvmTypeResolveResult jvmTypeResolveResult = ((PsiClassReferenceType) psiType).resolveType();
        if (jvmTypeResolveResult == null) {
            return substitutorTypeMap;
        }
        JvmSubstitutor substitutor = jvmTypeResolveResult.getSubstitutor();
        if (!(substitutor instanceof PsiJvmSubstitutor)) {
            return substitutorTypeMap;
        }
        PsiSubstitutor psiSubstitutor = ((PsiJvmSubstitutor) substitutor).getPsiSubstitutor();

        psiSubstitutor.getSubstitutionMap().forEach((k, v) -> {
            String genericName = k.getName();
            if (genericName != null && v != null) {
                substitutorTypeMap.put(genericName, v.getDeepComponentType());
            }
        });
        return substitutorTypeMap;
    }
}
