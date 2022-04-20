package org.metersphere.utils;

import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import org.metersphere.constants.Phase;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PsiClass相关操作工具类
 *
 * @author JustryDeng
 * @since 2021/12/18 10:18:04
 */
public final class PsiClassUtil {

    /** 找不到对应类时提示的格式 */
    private static final String CANNOT_DING_TYPE_TIPS_FORMAT = "[ERROR] Cannot found psi-class for type [%s].";

    /** 解析字段默认值失败 */
    private static final String CANNOT_PARSE_FIELD_DEFAULT_VALUE_FORMAT = "[ERROR] Parse default value for field-type [%s] error.";

    /** 字段注释的格式 */
    private static final String FIELD_COMMENT_FORMAT = "[注释] for [%s]";

    /** 循环引用提示格式 */
    private static final String CIRCULAR_REFERENCE_TIPS_FORMAT = "[WARN ] 存在循环引用. 此结构已体现, 同%s";

    /** 泛型分隔符正则 */
    private static final Pattern GENERIC_SPLIT_SIGN_REGEX_PATTERN = Pattern.compile("[,<>]");

    /** 枚举字段分隔符 */
    private static final String ENUM_ITEM_SPLIT_SIGN = " | ";

    /** {@link PsiClassUtil#mockJson(PsiType, Map, Map, boolean, int)}递归深度限制 */
    private static final int DEEP_LIMIT = 50;

    /** 常用基础包装类及其默认值信息 */
    private static final Map<String, Supplier<?>> BASE_TYPE_VALUE_SUPPLIER_MAP = new HashMap<>(16);

    static {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("Boolean", (Supplier<Boolean>) () -> false);
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("Byte", (Supplier<Byte>) () -> (byte)0);
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("Character", (Supplier<Character>) () -> (char)0);
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("Short", (Supplier<Short>) () -> (short)0);
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("Integer", (Supplier<Integer>) () -> 0);
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("Long", (Supplier<Long>) () -> 0L);
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("Float", (Supplier<Float>) () -> 0F);
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("Double", (Supplier<Double>) () -> 0D);
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("String", (Supplier<String>) () -> "");
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("BigDecimal", (Supplier<BigDecimal>) () -> BigDecimal.ZERO);
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("Date", (Supplier<String>) () -> dateTimeFormatter.format(LocalDateTime.now()));
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("Timestamp", (Supplier<Timestamp>) () -> new Timestamp(System.currentTimeMillis()));
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("LocalDate", (Supplier<LocalDate>) LocalDate::now);
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("LocalTime", (Supplier<LocalTime>) LocalTime::now);
        BASE_TYPE_VALUE_SUPPLIER_MAP.put("LocalDateTime", (Supplier<LocalDateTime>) LocalDateTime::now);
    }

    private PsiClassUtil() {
        throw new UnsupportedOperationException("Un-support to create instance");
    }

    /**
     * mock指定类的字段数据
     *
     * @see PsiClassUtil#mockJson(PsiType, Map, Map, boolean, int)
     */
    public static Object mockJson(PsiType psiType, Map<String, PsiType> genericNameAndSubstitutorTypeMapping,
                                  boolean containComment) {
        return mockJson(psiType, genericNameAndSubstitutorTypeMapping, null, containComment, 1);
    }

    /**
     * mock指定类的字段数据
     *
     * @param psiType
     *           目标类
     * @param genericNameAndSubstitutorTypeMapping
     *            psiType类中涉及到的泛型以及实际使用时该泛型对应的实际类
     * @param creatingAndCreatedPhasePsiTypeMap
     *            当前正在创建及已经创建了的类(k-带泛型的全类名， v-正在创建的类的标识 或 已创建了的类的类结构)
     * @param containComment
     *            是否获取字段的注释
     * @param curDeep
     *            当前递归深度
     * @return mock出数据结构
     */
    public static Object mockJson(PsiType psiType, Map<String, PsiType> genericNameAndSubstitutorTypeMapping,
                                  Map<String, Object> creatingAndCreatedPhasePsiTypeMap,
                                  boolean containComment, int curDeep) {
        if (curDeep > DEEP_LIMIT) {
            throw new RuntimeException("解析类型时解析深度超过上限[" + DEEP_LIMIT + "].");
        }
        if (psiType == null) {
            throw new RuntimeException("psiType cannot be null.");
        }
        String canonicalText = psiType.getCanonicalText();
        if ("void".equals(canonicalText)) {
            throw new RuntimeException("psiType cannot be void.");
        }

        // => 处理虚循环引用 & 缓存
        if (creatingAndCreatedPhasePsiTypeMap == null) {
            creatingAndCreatedPhasePsiTypeMap = new HashMap<>(4);
        }
        String fullNameWithGenericName = replaceGeneric(canonicalText, genericNameAndSubstitutorTypeMapping);
        if (creatingAndCreatedPhasePsiTypeMap.containsKey(fullNameWithGenericName)) {
            Object cacheObject = creatingAndCreatedPhasePsiTypeMap.get(fullNameWithGenericName);
            return Phase.CREATING == cacheObject ? String.format(CIRCULAR_REFERENCE_TIPS_FORMAT, fullNameWithGenericName) : cacheObject;
        } else {
            creatingAndCreatedPhasePsiTypeMap.put(fullNameWithGenericName, Phase.CREATING);
        }

        // => 系统类的话， 直接调用getTypeDefaultValue即可
        if (isSystemType(psiType))  {
            Object result = getTypeDefaultValue(psiType, null, genericNameAndSubstitutorTypeMapping, creatingAndCreatedPhasePsiTypeMap,
                    containComment, curDeep + 1);
            creatingAndCreatedPhasePsiTypeMap.put(fullNameWithGenericName, result);
            return result;
        }
        // => 数组的话， 直接调用getTypeDefaultValue即可
        if (psiType instanceof PsiArrayType) {
            Object result =  getTypeDefaultValue(psiType, null, genericNameAndSubstitutorTypeMapping, creatingAndCreatedPhasePsiTypeMap,
                    containComment, curDeep + 1);
            creatingAndCreatedPhasePsiTypeMap.put(fullNameWithGenericName, result);
            return result;
        }
        // => 下面需要解析类里面的字段了
        LinkedHashMap<String, Object> mockedInfoMap = new LinkedHashMap<>(16);
        PsiClass psiClass = null;
        if (psiType instanceof PsiClassType) {
            psiClass = ((PsiClassType) psiType).resolve();
        }
        PsiField[] allFields = psiClass != null ? psiClass.getAllFields() : new PsiField[]{};
        for (PsiField field : allFields) {
            if (skipMock(field)) {
                continue;
            }
            PsiType type = field.getType();
            String name = field.getName();
            // 添加注释
            if (containComment) {
                PsiDocComment docComment = field.getDocComment();
                if (docComment != null && docComment.getText() != null) {
                    // TODO: 2022/4/20 注释不添加
                    // mockedInfoMap.put(String.format(FIELD_COMMENT_FORMAT, name), docComment.getText());
                }
            }

            canonicalText = type.getCanonicalText();
            if (typeIsGeneric(type) && genericNameAndSubstitutorTypeMapping != null && genericNameAndSubstitutorTypeMapping.containsKey(canonicalText)) {
                mockedInfoMap.put(name, getTypeDefaultValue(type, genericNameAndSubstitutorTypeMapping.get(canonicalText),
                        null, creatingAndCreatedPhasePsiTypeMap, containComment, curDeep + 1));
            } else if (typeContainGeneric(type)) {
                mockedInfoMap.put(name, getTypeDefaultValue(type, null,
                        genericNameAndSubstitutorTypeMapping, creatingAndCreatedPhasePsiTypeMap, containComment, curDeep + 1));
            } else {
                mockedInfoMap.put(name, getTypeDefaultValue(type, null, null, creatingAndCreatedPhasePsiTypeMap,
                        containComment, curDeep + 1));
            }
        }
        creatingAndCreatedPhasePsiTypeMap.put(fullNameWithGenericName, mockedInfoMap);
        return mockedInfoMap;
    }

    /**
     * 填充canonicalText中的泛型
     *
     * @param canonicalText
     *            可能带有泛型的全类名
     * @param genericNameAndSubstitutorTypeMapping
     *            canonicalText中涉及到的泛型以及实际使用时该泛型对应的实际类
     * @return  使用对应实际类替换泛型占位符后的 全类名
     */
    private static String replaceGeneric(String canonicalText, Map<String, PsiType> genericNameAndSubstitutorTypeMapping) {
        Matcher matcher = GENERIC_SPLIT_SIGN_REGEX_PATTERN.matcher(canonicalText);
        int from = 0;
        List<Integer> genericIndexList = new ArrayList<>(8);
        while (matcher.find(from)) {
            final int currStart = matcher.start();
            final int currEnd = matcher.end();
            genericIndexList.add(currStart);
            from = currEnd;
        }

        int size = genericIndexList.size();
        StringBuilder newCanonicalText;
        if (size == 0) {
            newCanonicalText = new StringBuilder(canonicalText);
        } else {
            newCanonicalText = new StringBuilder();
            for (int i = 0; i < size; i++) {
                Integer currIndex = genericIndexList.get(i);
                if (i == size - 1) {
                    newCanonicalText.append(canonicalText.charAt(currIndex));
                    break;
                }
                if (i == 0) {
                    newCanonicalText = new StringBuilder(canonicalText.substring(0, currIndex));
                }
                Integer nextIndex = genericIndexList.get(i + 1);
                String maybeGenericName = canonicalText.substring(currIndex + 1, nextIndex);
                if (maybeGenericName.trim().length() != 0) {
                    String realTypeName = maybeGenericName;
                    if (genericNameAndSubstitutorTypeMapping != null && genericNameAndSubstitutorTypeMapping.containsKey(maybeGenericName)) {
                        realTypeName = genericNameAndSubstitutorTypeMapping.get(maybeGenericName).getCanonicalText();
                    }
                    newCanonicalText.append(canonicalText.charAt(currIndex)).append(realTypeName);
                }
            }
        }
        return newCanonicalText.toString();
    }

    /**
     * 获取指定类型的默认值
     *
     * @param psiType
     *            类型
     * @param genericSubstitutorType
     *            当psiType是泛型时时，若genericSubstitutorType不为null,则采用genericSubstitutorType类型的默认值
     * @param containGenericSubstitutorTypeMapping
     *            psiType中包含的泛型的映射关系
     * @param creatingAndCreatedPhasePsiTypeMap
     *            当前正在创建及已经创建了的类(k-带泛型的全类名， v-创建了的类标识)
     * @param containComment
     *            是否包含注释
     * @param curDeep
     *            当前递归深度
     * @return  指定类型的默认值
     */
    @SuppressWarnings("MissingRecentApi")
    private static Object getTypeDefaultValue(PsiType psiType, PsiType genericSubstitutorType,
                                              Map<String, PsiType> containGenericSubstitutorTypeMapping,
                                              Map<String, Object> creatingAndCreatedPhasePsiTypeMap,
                                              boolean containComment, int curDeep) {
        // => primitive type
        if (psiType instanceof PsiPrimitiveType) {
            return PsiTypesUtil.getDefaultValue(psiType);
        }
        // => normal reference type
        String fieldTypeName = psiType.getPresentableText();
        if (isBaseType(fieldTypeName)) {
            return BASE_TYPE_VALUE_SUPPLIER_MAP.get(fieldTypeName).get();
        }
        // => other reference type
        // 数组
        if (psiType instanceof PsiArrayType) {
            final PsiType deepType = psiType.getDeepComponentType();
            List<Object> list = new ArrayList<>(1);
            final String deepTypeName = deepType.getPresentableText();
            if (deepType instanceof PsiPrimitiveType) {
                list.add(PsiTypesUtil.getDefaultValue(deepType));
            } else if (isBaseType(deepTypeName)) {
                list.add(BASE_TYPE_VALUE_SUPPLIER_MAP.get(deepTypeName).get());
            } else if (typeIsGeneric(deepType) && containGenericSubstitutorTypeMapping != null && containGenericSubstitutorTypeMapping.containsKey(deepTypeName)) {
                list.add(getTypeDefaultValue(deepType, containGenericSubstitutorTypeMapping.get(deepTypeName), containGenericSubstitutorTypeMapping,
                        creatingAndCreatedPhasePsiTypeMap, containComment, curDeep + 1));
            } else {
                PsiClass psiClass = PsiUtil.resolveClassInType(deepType);
                if (psiClass == null) {
                    list.add(String.format(CANNOT_DING_TYPE_TIPS_FORMAT, deepTypeName));
                } else {
                    list.add(mockJson(deepType, PsiTypeUtil.parseGenericNameAndSubstitutorTypeMapping(deepType),
                            creatingAndCreatedPhasePsiTypeMap, containComment, curDeep + 1));
                }
            }
            return list;
        }
        // 集合
        if (typeIsIterable(psiType)) {
            PsiType iterableType = PsiUtil.extractIterableTypeParameter(psiType, false);
            if (iterableType == null) {
                return String.format(CANNOT_DING_TYPE_TIPS_FORMAT, fieldTypeName);
            }
            List<Object> list = new ArrayList<>(1);
            PsiClass iterableClass = PsiUtil.resolveClassInClassTypeOnly(iterableType);
            if (iterableClass == null) {
                String canonicalText = iterableType.getCanonicalText();
                if (!"?".equals(canonicalText)) {
                    return String.format(CANNOT_PARSE_FIELD_DEFAULT_VALUE_FORMAT, fieldTypeName);
                }
            } else {
                String classTypeName = iterableClass.getName();
                if (isBaseType(classTypeName)) {
                    list.add(BASE_TYPE_VALUE_SUPPLIER_MAP.get(classTypeName).get());
                } else if (typeIsGeneric(iterableType) && containGenericSubstitutorTypeMapping != null && containGenericSubstitutorTypeMapping.containsKey(classTypeName)) {
                    list.add(getTypeDefaultValue(iterableType, containGenericSubstitutorTypeMapping.get(classTypeName),
                            containGenericSubstitutorTypeMapping, creatingAndCreatedPhasePsiTypeMap, containComment, curDeep + 1));
                } else {
                    list.add(mockJson(iterableType, PsiTypeUtil.parseGenericNameAndSubstitutorTypeMapping(iterableType),
                            creatingAndCreatedPhasePsiTypeMap, containComment, curDeep + 1));
                }
            }
            return list;
        }
        // 泛型
        if (typeIsGeneric(psiType)) {
            // 泛型替换者
            if (genericSubstitutorType != null) {
                return getTypeDefaultValue(genericSubstitutorType, null, containGenericSubstitutorTypeMapping, creatingAndCreatedPhasePsiTypeMap,
                        containComment, curDeep + 1);
            }
            return new HashMap<>(1);
        }
        // 未知类型
        PsiClass psiClass = PsiUtil.resolveClassInType(psiType);
        if (psiClass == null) {
            return String.format(CANNOT_PARSE_FIELD_DEFAULT_VALUE_FORMAT, fieldTypeName);
        }
        // 枚举
        if (psiClass.isEnum()) {
            final PsiField[] fields = psiClass.getFields();
            String enumInfos = "";
            for (PsiField field : fields) {
                if (field instanceof PsiEnumConstant) {
                    enumInfos = enumInfos.length() == 0 ? field.getName() : enumInfos + ENUM_ITEM_SPLIT_SIGN + field.getName();
                }
            }
            return enumInfos;
        }
        // 其它jdk自带的类
        if (isJdkType(psiType)) {
            return new HashMap<>(1);
        }
        // 其它类型
        String canonicalText = psiType.getCanonicalText();
        String fullNameWithGenericName = replaceGeneric(canonicalText, containGenericSubstitutorTypeMapping);
        if (creatingAndCreatedPhasePsiTypeMap != null && creatingAndCreatedPhasePsiTypeMap.containsKey(fullNameWithGenericName)) {
            Object cacheObject = creatingAndCreatedPhasePsiTypeMap.get(fullNameWithGenericName);
            return Phase.CREATING == cacheObject ? String.format(CIRCULAR_REFERENCE_TIPS_FORMAT, fullNameWithGenericName) : cacheObject;
        }
        Map<String, PsiType> newGenericNameAndSubstitutorTypeMapping = new HashMap<>(PsiTypeUtil.parseGenericNameAndSubstitutorTypeMapping(psiType));
        if (containGenericSubstitutorTypeMapping != null) {
            newGenericNameAndSubstitutorTypeMapping.putAll(containGenericSubstitutorTypeMapping);
        }
        Object result = mockJson(psiType, newGenericNameAndSubstitutorTypeMapping, creatingAndCreatedPhasePsiTypeMap, containComment, curDeep + 1);
        if (creatingAndCreatedPhasePsiTypeMap != null) {
            creatingAndCreatedPhasePsiTypeMap.put(fullNameWithGenericName, result);
        }
        return result;
    }


    /**
     * psiType是否是集合
     *
     * @param psiType
     *            类
     * @return  psiType是否是集合
     */
    private static boolean typeIsIterable(PsiType psiType) {
        Objects.requireNonNull(psiType, "psiType cannot be null.");
        final Set<PsiType> currSuperTypes = Arrays.stream(psiType.getSuperTypes()).collect(Collectors.toSet());
        Set<String> allSuperTypes = currSuperTypes.stream().map(PsiType::getPresentableText).collect(Collectors.toSet());
        allSuperTypes.add(psiType.getPresentableText());
        while (currSuperTypes.size() != 0) {
            Set<PsiType> tmpSuperTypes = new HashSet<>();
            for (PsiType currSuperType : currSuperTypes) {
                final PsiType[] superTypes = currSuperType.getSuperTypes();
                tmpSuperTypes.addAll(Arrays.stream(superTypes).collect(Collectors.toSet()));
            }
            currSuperTypes.clear();
            currSuperTypes.addAll(tmpSuperTypes);
            allSuperTypes.addAll(tmpSuperTypes.stream().map(PsiType::getPresentableText).collect(Collectors.toSet()));
        }
        return allSuperTypes.stream().map(typeName -> {
            final int genericStartIndex = typeName.indexOf("<");
            return typeName.substring(0, genericStartIndex > 0 ? genericStartIndex : typeName.length());
        }).anyMatch(x -> x.equals(Iterable.class.getSimpleName()));
    }


    /**
     * psiType是否是泛型
     *
     * @param psiType
     *            类
     * @return  psiType是否是泛型
     */
    private static boolean typeIsGeneric(PsiType psiType) {
        Objects.requireNonNull(psiType, "psiType cannot be null.");
        // 非包装类
        if (psiType instanceof PsiPrimitiveType) {
            return false;
        }
        return !psiType.getCanonicalText().contains(".");
    }


    /**
     * psiType中是否包含泛型
     *
     * @param psiType
     *            类
     * @return  psiType中是否包含泛型
     */
    private static boolean typeContainGeneric(PsiType psiType) {
        Objects.requireNonNull(psiType, "psiType cannot be null.");
        // 非包装类
        if (psiType instanceof PsiPrimitiveType) {
            return false;
        }
        String canonicalText = psiType.getCanonicalText();
        String[] allTypeName = canonicalText.split("[,<>]");
        return Arrays.stream(allTypeName).anyMatch(x -> !x.contains("."));
    }

    /**
     * 是否是系统类
     *
     * @param psiType
     *            类
     * @return  是否是系统类
     */
    private static boolean isSystemType(PsiType psiType) {
        return psiType instanceof  PsiPrimitiveType || isBaseType(psiType.getPresentableText()) || isJdkType(psiType);
    }

    /**
     * 是否是jdk类
     *
     * @param psiType
     *            类
     * @return  是否是jdk类
     */
    private static boolean isJdkType(PsiType psiType) {
        String canonicalText = psiType.getCanonicalText();
        return canonicalText.startsWith("java.");
    }

    /**
     * 是否跳过指定的字段
     *
     * @param psiField
     *            字段
     * @return  是否跳过指定的字段
     */
    private static boolean skipMock(PsiField psiField) {
        return psiField != null && (
                psiField.hasModifierProperty(PsiModifier.STATIC)
                        || psiField.hasModifierProperty(PsiModifier.TRANSIENT)
        );
    }

    /**
     * 是否是常用基础类型
     *
     * @param typeName
     *            类型名称
     * @return  是否是常用基础类型
     */
    private static boolean isBaseType (String typeName) {
        return BASE_TYPE_VALUE_SUPPLIER_MAP.containsKey(typeName);
    }

}
