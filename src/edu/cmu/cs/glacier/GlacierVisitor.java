package edu.cmu.cs.glacier;

/*>>>
import org.checkerframework.checker.compilermsgs.qual.CompilerMessageKey;
*/

import java.lang.annotation.Annotation;
import java.util.*;

import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

import com.sun.source.tree.*;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.framework.type.AnnotatedTypeParameterBounds;
import org.checkerframework.framework.type.QualifierHierarchy;
import org.checkerframework.framework.type.TypeHierarchy;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedDeclaredType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedArrayType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedExecutableType;
import org.checkerframework.framework.type.AnnotatedTypeMirror.AnnotatedTypeVariable;
import org.checkerframework.framework.util.ContractsUtils;
import org.checkerframework.framework.util.FlowExpressionParseUtil;
import org.checkerframework.framework.util.FlowExpressionParseUtil.FlowExpressionContext;
import org.checkerframework.framework.util.FlowExpressionParseUtil.FlowExpressionParseException;
import org.checkerframework.javacutil.*;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeValidator;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.dataflow.analysis.FlowExpressions;
import org.checkerframework.dataflow.analysis.FlowExpressions.Receiver;
import org.checkerframework.dataflow.qual.Pure;
import org.checkerframework.dataflow.util.PurityUtils;
import org.checkerframework.framework.source.Result;

import com.sun.source.tree.Tree.Kind;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.tree.JCTree;

import edu.cmu.cs.glacier.qual.*;

public class GlacierVisitor extends BaseTypeVisitor<GlacierAnnotatedTypeFactory> {

	public GlacierVisitor(BaseTypeChecker checker) {
		super(checker);
	}
	
    @Override
    protected BaseTypeValidator createTypeValidator() {
        return new GlacierTypeValidator(checker, this, atypeFactory);
    }
	
	private static boolean modifiersIncludeModifier(ModifiersTree modifiers, Class<? extends Annotation> modifier) {
		boolean foundImmutable = false;
		
		List <? extends AnnotationTree> annotations = modifiers.getAnnotations();
		for (AnnotationMirror a : InternalUtils.annotationsFromTypeAnnotationTrees(annotations)) {
	        if (AnnotationUtils.areSameByClass(a,modifier)) {
	        	foundImmutable = true;
	        }
		}
		
		return foundImmutable;
	}

    private boolean typeIsImmutable(AnnotatedTypeMirror type) {
        TypeMirror underlyingType = type.getUnderlyingType();

        boolean fieldIsImmutable = type.hasAnnotation(Immutable.class);
        if (!fieldIsImmutable) {
            // Check for permitted special cases: primitives and type parameters.
            boolean typeIsPrimitive = underlyingType instanceof PrimitiveType;
            boolean typeIsImmutableClassTypeParameter = false;
            if (underlyingType.getKind() == TypeKind.TYPEVAR) {
                // Type variables can be assumed to be immutable if they are on an immutable class. Otherwise they are unsafe.
                TypeVariable typeVariable = (TypeVariable) underlyingType;
                TypeParameterElement typeVariableElement = (TypeParameterElement) (typeVariable.asElement());
                Element elementEnclosingTypeVariable = typeVariableElement.getGenericElement();
                if (elementEnclosingTypeVariable.getKind() == ElementKind.CLASS || elementEnclosingTypeVariable.getKind() == ElementKind.ENUM) {
                    // Check to see if this class is immutable.
                    TypeElement classElement = (TypeElement) elementEnclosingTypeVariable;
                    AnnotatedTypeMirror classTypeMirror = atypeFactory.getAnnotatedType(classElement);
                    if (classTypeMirror.hasAnnotation(Immutable.class)) {
                        typeIsImmutableClassTypeParameter = true;
                    }
                }
            }

            if (!typeIsPrimitive && !typeIsImmutableClassTypeParameter) {
                return false;
            }
        }

        if (underlyingType.getKind() == TypeKind.ARRAY) {
            // Arrays must be immutable arrays of immmutable objects.
            AnnotatedArrayType arrayType = (AnnotatedArrayType) type;
            AnnotatedTypeMirror componentType = arrayType.getComponentType();
            return typeIsImmutable(componentType);
        }

        return true;
    }

    private void checkFieldIsImmutable(ClassTree deepestClassTree, Tree containingTree, TypeElement immediateContainingElement, Element element, boolean alsoCheckFinal) {
        AnnotatedTypeMirror fieldType = atypeFactory.getAnnotatedType(element);
        if (!typeIsImmutable(fieldType)) {
            if (alsoCheckFinal) {
                checker.report(Result.failure("glacier.mutablemember", deepestClassTree.getSimpleName(), immediateContainingElement, element), deepestClassTree);
            } else {
                if (fieldType.getUnderlyingType().getKind() == TypeKind.ARRAY) {
                    AnnotatedArrayType arrayType = (AnnotatedArrayType)fieldType;
                    if (!arrayType.hasAnnotation(Immutable.class)) {
                        checker.report(Result.failure("glacier.mutable.wholearray.invalid"), element);
                    }
                    else {
                        checker.report(Result.failure("glacier.mutable.array.invalid"), element);
                    }
                }
                else {
                    checker.report(Result.failure("glacier.mutable.invalid"), element);
                }
            }
        }

        if (alsoCheckFinal) {
            if (!ElementUtils.isFinal(element)) {
                checker.report(Result.failure("glacier.nonfinalmember", deepestClassTree.getSimpleName(), immediateContainingElement, element), deepestClassTree);
            }
        }
    }

    private void checkElementMembersAreImmutable(ClassTree outermostTree, Tree containingTree, Element elem, boolean alsoCheckFinal) {
        if (elem.getKind() == ElementKind.CLASS || elem.getKind() == ElementKind.ENUM) {
            TypeElement typeElement = (TypeElement)elem;
            List<? extends Element> elements = typeElement.getEnclosedElements();


            for (Element e : elements) {
                if (e.getKind() == ElementKind.FIELD) {
                    // Check to make sure this field is immutable and final.
                    checkFieldIsImmutable(outermostTree, containingTree, typeElement, e, alsoCheckFinal);
                }
            }

            TypeMirror superclassType = typeElement.getSuperclass();
            if (superclassType != null && superclassType.getKind() == TypeKind.DECLARED) {
                DeclaredType superclassDeclaredType = (DeclaredType)superclassType;
                Element superclassElement = superclassDeclaredType.asElement();
                checkElementMembersAreImmutable(outermostTree, containingTree, superclassElement, alsoCheckFinal);
            }
        }
    }

    private void checkAllClassMembersAreImmutable(ClassTree outermostTree, Tree containingTree, boolean alsoCheckFinal) {
        if (containingTree instanceof ExpressionTree) {
            Element elem = TreeUtils.elementFromUse((ExpressionTree)containingTree);
            checkElementMembersAreImmutable(outermostTree, containingTree, elem, alsoCheckFinal);
        }
    }



    private void checkImmediateMembersAreImmutable(ClassTree classTree, boolean alsoCheckFinal) {
        TypeElement classTreeAsElement = TreeUtils.elementFromDeclaration(classTree);
        List <? extends Tree> members = classTree.getMembers();
        for (Tree t : members) {
            if (t.getKind() == Kind.VARIABLE) {
                checkFieldIsImmutable(classTree, classTree, classTreeAsElement, TreeUtils.elementFromDeclaration((VariableTree) t), false);
            }
        }
    }

	@Override
	public Void visitClass(ClassTree node, Void p) {
		super.visitClass(node, p);
		
		boolean classIsImmutable = modifiersIncludeModifier(node.getModifiers(), Immutable.class);
		boolean classIsReadonly = modifiersIncludeModifier(node.getModifiers(), ReadOnly.class);

		if (classIsReadonly) {
			checker.report(Result.failure("glacier.readonly.class"), node);
		}
		
		if (classIsImmutable) {
			// Check to make sure all fields are immutable.
            checkImmediateMembersAreImmutable(node, false);
		}
		
		Tree superclass = node.getExtendsClause();
		if (superclass != null) {
			AnnotatedTypeMirror superclassType = atypeFactory.getAnnotatedType(superclass);
			if (superclassType.hasAnnotation(Immutable.class) && !classIsImmutable) {
				checker.report(Result.failure("glacier.subclass.mutable", node.getSimpleName(), superclass.toString()), node);
			}
            else if (classIsImmutable && !superclassType.hasAnnotation(Immutable.class)) {
                // Check members of all superclasses to make sure they're all immutable and final.
                checkAllClassMembersAreImmutable(node, superclass, true);
            }
		}

		
		// Check to make sure that if any implemented interface is immutable, the class is immutable.
		List<? extends Tree> interfaces = node.getImplementsClause();
		for (Tree implementedInterface : interfaces) {
				AnnotatedTypeMirror interfaceType = atypeFactory.getAnnotatedType(implementedInterface);
				
				if (interfaceType.hasAnnotation(Immutable.class)) {
					if (!classIsImmutable) {
						checker.report(Result.failure("glacier.interface.immutable", node.getSimpleName(), implementedInterface), node);
					}
					break;	
				}
		}
		
		return null;
	}
	
	/* 
	 * In addition to the superclass implementation, this checks to make sure that if this assignment is to a field,
	 * the containing class is mutable. 
	 * @see org.checkerframework.common.basetype.BaseTypeVisitor#visitAssignment(com.sun.source.tree.AssignmentTree, java.lang.Void)
	 */
    @Override
    public Void visitAssignment(AssignmentTree node, Void p) {
    	super.visitAssignment(node, p);
    	
		ExpressionTree variable = node.getVariable();
		
		if (TreeUtils.isFieldAccess(variable)) {
			AnnotatedTypeMirror ownerType = null;

			if (variable.getKind().equals(Tree.Kind.MEMBER_SELECT)) {
				// explicit field access
				MemberSelectTree memberSelect = (MemberSelectTree) variable;
				ownerType = atypeFactory.getAnnotatedType(memberSelect.getExpression());            
			} else if (variable.getKind().equals(Tree.Kind.IDENTIFIER)) {
				// implicit field access
				// Need to know the type of the containing class.
				ownerType = visitorState.getClassType();
			}

			// Assignment to fields of immutable classes is allowed during their constructors and outside methods.
			MethodTree methodTree = visitorState.getMethodTree();
			if (methodTree != null) {
				boolean methodIsConstructor = TreeUtils.isConstructor(methodTree);

				AnnotatedDeclaredType classType = visitorState.getClassType();

                // Even in constructors, you can't assign to fields of OTHER classes.
				boolean classOwnsAssignedField = ownerType.getUnderlyingType().equals(classType.getUnderlyingType()); 
				AnnotationMirror ownerAnnotationMirror = ownerType.getAnnotationInHierarchy(atypeFactory.READ_ONLY);

                Element fieldElement = TreeUtils.elementFromUse(variable);
                boolean fieldIsStatic = ElementUtils.isStatic(fieldElement);

				if ((!methodIsConstructor || !classOwnsAssignedField || fieldIsStatic) &&
					!atypeFactory.getQualifierHierarchy().isSubtype(ownerAnnotationMirror, atypeFactory.MAYBE_MUTABLE)) {
					checker.report(Result.failure("glacier.assignment"), node);
				}
			}
		}
		else if (variable.getKind() == Tree.Kind.ARRAY_ACCESS) {
			ArrayAccessTree arrayAccessTree = (ArrayAccessTree)variable;
			
			AnnotatedTypeMirror arrayType = atypeFactory.getAnnotatedType(arrayAccessTree.getExpression());
			AnnotationMirror arrayTypeAnnotation = arrayType.getAnnotationInHierarchy(atypeFactory.READ_ONLY);
			if (!atypeFactory.getQualifierHierarchy().isSubtype(arrayTypeAnnotation, atypeFactory.MAYBE_MUTABLE)) {
				checker.report(Result.failure("glacier.assignment.array"), node);
			}
		}

    	return null;
    }

    /**
     * Tests that the qualifiers present on the useType are valid qualifiers,
     * given the qualifiers on the declaration of the type, declarationType.
     * 
     * For Glacier, we don't allow qualifiers on the useType that are not redundant -- except that for Object, anything goes.
     */
    @Override
    public boolean isValidUse(AnnotatedDeclaredType declarationType,
            AnnotatedDeclaredType useType, Tree tree) {
    	
    	// Users can't specify bottom annotations.
    	if (useType.hasAnnotation(GlacierBottom.class)) {
    		return false;
    	}
    	
        if(TypesUtils.isObject(declarationType.getUnderlyingType())) {
            // If the declared type is Object, the use can have any Glacier annotation.
            return true;
        }

        // We need to make sure that users never apply annotations that conflict with the ones in the declarations. 
        // Check that directly rather than calling super.isValidUse().
        AnnotationMirror immutableAnnotation = AnnotationUtils.fromClass(elements, Immutable.class);
        AnnotationMirror useAnnotation = useType.getAnnotationInHierarchy(immutableAnnotation);

        if (useAnnotation != null) {
        	return declarationType.hasAnnotation(useAnnotation);
        }
        else {
        	return !declarationType.hasAnnotation(useAnnotation);
        }
    }
    
    protected Set<? extends AnnotationMirror> getExceptionParameterLowerBoundAnnotations() {
    	java.util.HashSet<AnnotationMirror> h = new java.util.HashSet<> (2);
    	
    	h.add(AnnotationUtils.fromClass(elements, GlacierBottom.class));
    	
        return h;
    }
    
    protected Set<? extends AnnotationMirror> getThrowUpperBoundAnnotations() {
        return atypeFactory.getQualifierHierarchy().getTopAnnotations();
    }
    
    
    protected void commonAssignmentCheck(AnnotatedTypeMirror varType,
            AnnotatedTypeMirror valueType, Tree valueTree, /*@CompilerMessageKey*/ 
    String errorKey) {
    	// It's okay to assign from an immutable class to an variable of type Object,
    	// even if the variable is mutable.
    	
    	if (TypesUtils.isObject(varType.getUnderlyingType()) && varType.hasAnnotation(MaybeMutable.class)) {
    		// We're assigning to something of type @MaybeMutable Object, so its annotations don't matter.
    		// No other checks to do.
    	}
    	else {
              super.commonAssignmentCheck(varType, valueType, valueTree, errorKey);
    	}
    	
    }

    /*
    protected boolean skipReceiverSubtypeCheck(MethodInvocationTree node,
            AnnotatedTypeMirror methodDefinitionReceiver,
            AnnotatedTypeMirror methodCallReceiver) {
    	// If the method takes Object, it can take an @Immutable object too.
    	return ((types.directSupertypes(methodDefinitionReceiver.getUnderlyingType())).size() == 0);

    }*/

    @Override
    protected void checkTypecastSafety(TypeCastTree node, Void p) {
    	// For now, do nothing. There's nothing to check that isn't already expressed by Java's type system.
    }

    // Type arguments to an immutable class must be immutable because those type arguments may be used on fields.
    // Type arguments on methods don't need any special checking.
    protected void checkTypeArguments(Tree toptree, List<? extends AnnotatedTypeParameterBounds> paramBounds, List<? extends AnnotatedTypeMirror> typeargs, List<? extends Tree> typeargTrees) {
        super.checkTypeArguments(toptree, paramBounds, typeargs, typeargTrees);

        AnnotatedTypeMirror toptreeType = atypeFactory.getAnnotatedType(toptree);

        if (toptreeType.hasAnnotation(Immutable.class) && (toptree.getKind() == Kind.CLASS || toptree.getKind() == Kind.PARAMETERIZED_TYPE)) {
            // Cases for toptree: ParameterizedTypeTree; MethodInvocationTree; NewClassTree.

            // Make sure all the type arguments have the @Immutable annotation.
            for (AnnotatedTypeMirror typearg : typeargs) {
                // Ignore type variables because we only want to check concrete types.
                if (typearg.getKind() != TypeKind.TYPEVAR && !typearg.hasAnnotation(Immutable.class)) {
                    // One last-ditch check: maybe typearg is a wildcard. If so, it suffices if the upper bound is immutable or a type variable.
                    boolean reportError = false;

                    if (typearg.getKind() == TypeKind.WILDCARD) {
                        AnnotatedTypeMirror.AnnotatedWildcardType annotatedWildcardType = (AnnotatedTypeMirror.AnnotatedWildcardType) typearg;
                        AnnotatedTypeMirror extendsBound = annotatedWildcardType.getExtendsBound();
                        if (extendsBound.getKind() != TypeKind.TYPEVAR && !extendsBound.hasAnnotation(Immutable.class)) {
                            reportError = true;
                        }
                    }
                    else {
                        reportError = true;
                    }

                    if (reportError) {

                        checker.report(Result.failure("glacier.typeparameter.mutable", toptree, typearg), toptree);

                    }
                }
            }
        }
    }

    /**
     * Indicates whether to skip subtype checks on the receiver when
     * checking method invocability. A visitor may, for example,
     * allow a method to be invoked even if the receivers are siblings
     * in a hierarchy, provided that some other condition (implemented
     * by the visitor) is satisfied.
     *
     * @param node                        the method invocation node
     * @param methodDefinitionReceiver    the ATM of the receiver of the method definition
     * @param methodCallReceiver          the ATM of the receiver of the method call
     *
     * @return whether to skip subtype checks on the receiver
     */
    @Override
    protected boolean skipReceiverSubtypeCheck(MethodInvocationTree node,
            AnnotatedTypeMirror methodDefinitionReceiver,
            AnnotatedTypeMirror methodCallReceiver) {
    	
    	TypeMirror definitionType = methodDefinitionReceiver.getUnderlyingType();
    	
    	// It's okay to invoke methods that are defined on java.lang.Object or on java.lang.Enum.
    	if (TypesUtils.isObject(definitionType)) {
    		return true;
    	}
    	else if (definitionType instanceof DeclaredType) {
    		DeclaredType declaredDefinitionType = (DeclaredType)definitionType;
    		return TypesUtils.getQualifiedName(declaredDefinitionType).contentEquals("java.lang.Enum");
    	}
    	return false;
    			
    }
    
    /**
     * MJC: This override is here solely so I can modify OverrideChecker, which is private. There are no other changes.
     * Type checks that a method may override another method.
     * Uses the OverrideChecker class.
     *
     * @param overriderTree Declaration tree of overriding method
     * @param overridingType Type of overriding class
     * @param overridden Type of overridden method
     * @param overriddenType Type of overridden class
     * @return true if the override is allowed
     */
    @Override
    protected boolean checkOverride(MethodTree overriderTree,
                                    AnnotatedDeclaredType overridingType,
                                    AnnotatedExecutableType overridden,
                                    AnnotatedDeclaredType overriddenType,
                                    Void p) {

        // Get the type of the overriding method.
        AnnotatedExecutableType overrider =
                atypeFactory.getAnnotatedType(overriderTree);

        // This needs to be done before overrider.getReturnType() and overridden.getReturnType()
        if (overrider.getTypeVariables().isEmpty()
                && !overridden.getTypeVariables().isEmpty()) {
            overridden = overridden.getErased();
        }

        GlacierOverrideChecker overrideChecker = new GlacierOverrideChecker(
                overriderTree,
                overrider, overridingType, overrider.getReturnType(),
                overridden, overriddenType, overridden.getReturnType());

        return overrideChecker.checkOverride();
    }


    /**
     * Class to perform method override and method reference checks.
     *
     * Method references are checked similarly to method overrides, with the
     * method reference viewed as overriding the functional interface's method.
     *
     * Checks that an overriding method's return type, parameter types, and
     * receiver type are correct with respect to the annotations on the
     * overridden method's return type, parameter types, and receiver type.
     *
     * <p>
     * Furthermore, any contracts on the method must satisfy behavioral
     * subtyping, that is, postconditions must be at least as strong as the
     * postcondition on the superclass, and preconditions must be at most as
     * strong as the condition on the superclass.
     *
     * <p>
     * This method returns the result of the check, but also emits error
     * messages as a side effect.
     */
    private class GlacierOverrideChecker {
        // Strings for printing
        private final String overriderMeth;
        private final String overriderTyp;
        private final String overriddenMeth;
        private final String overriddenTyp;

        private final Tree overriderTree;
        private final Boolean methodReference;

        private final AnnotatedExecutableType overrider;
        private final AnnotatedTypeMirror overridingType;
        private final AnnotatedExecutableType overridden;
        private final AnnotatedDeclaredType overriddenType;
        private final AnnotatedTypeMirror overriddenReturnType;
        private final AnnotatedTypeMirror overridingReturnType;

        /**
         * Create an OverrideChecker.
         *
         * Notice that the return types are passed in separately. This is to
         * support some types of method references where the overrider's return
         * type is not the appropriate type to check.
         *
         * @param overriderTree
         *            the AST node of the overriding method or method reference
         * @param overrider
         *            the type of the overriding method
         * @param overridingType
         *            the type enclosing the overrider method, usually an AnnotatedDeclaredType;
         *            for Method References may be something else.
         * @param overridingReturnType
         *            the return type of the overriding method
         * @param overridden
         *            the type of the overridden method
         * @param overriddenType
         *            the declared type enclosing the overridden method
         * @param overriddenReturnType
         *            the return type of the overridden method
         */
        GlacierOverrideChecker(Tree overriderTree,
                        AnnotatedExecutableType overrider,
                        AnnotatedTypeMirror overridingType,
                        AnnotatedTypeMirror overridingReturnType,
                        AnnotatedExecutableType overridden,
                        AnnotatedDeclaredType overriddenType,
                        AnnotatedTypeMirror overriddenReturnType) {

            this.overriderTree = overriderTree;
            this.overrider = overrider;
            this.overridingType = overridingType;
            this.overridden = overridden;
            this.overriddenType = overriddenType;
            this.overriddenReturnType = overriddenReturnType;
            this.overridingReturnType = overridingReturnType;

            overriderMeth = overrider.toString();
            if (overridingType.getKind() == TypeKind.DECLARED) {
                DeclaredType overriderTypeMirror = ((AnnotatedDeclaredType)overridingType).getUnderlyingType();
                overriderTyp = overriderTypeMirror.asElement().toString();
            } else {
                overriderTyp = overridingType.toString();
            }
            overriddenMeth = overridden.toString();
            overriddenTyp = overriddenType.getUnderlyingType().asElement().toString();

            this.methodReference = overriderTree.getKind() == Tree.Kind.MEMBER_REFERENCE;
        }

        /**
         * Perform the check
         *
         * @return true if the override is allowed
         */
        public boolean checkOverride() {
            if (checker.shouldSkipUses(overriddenType.getUnderlyingType().asElement())) {
                return true;
            }

            boolean result = checkReturn();
            result &= checkParameters();
            if (methodReference) {
                result &= checkMemberReferenceReceivers();
            } else {
                result &= checkReceiverOverride();
            }
            checkPreAndPostConditions();
            checkPurity();

            return result;
        }

        private void checkPurity() {
            String msgKey = methodReference ? "purity.invalid.methodref" : "purity.invalid.overriding";

            // check purity annotations
            Set<Pure.Kind> superPurity = new HashSet<Pure.Kind>(
                    PurityUtils.getPurityKinds(atypeFactory,
                            overridden.getElement()));
            Set<Pure.Kind> subPurity = new HashSet<Pure.Kind>(
                    PurityUtils.getPurityKinds(atypeFactory, overrider.getElement()));
            if (!subPurity.containsAll(superPurity)) {
                checker.report(Result.failure(msgKey,
                        overriderMeth, overriderTyp, overriddenMeth, overriddenTyp,
                        subPurity, superPurity), overriderTree);
            }
        }

        private void checkPreAndPostConditions() {
            String msgKey = methodReference ? "methodref" : "override";
            if (methodReference) {
                // TODO: Support post conditions and method references.
                // The parse context always expects instance methods, but method references can be static.
                return;
            }

            // Check postconditions
            ContractsUtils contracts = ContractsUtils.getInstance(atypeFactory);
            Set<ContractsUtils.PreOrPostcondition> superPost = contracts
                    .getPostconditions(overridden.getElement());
            Set<ContractsUtils.PreOrPostcondition> subPost = contracts
                    .getPostconditions(overrider.getElement());
            Set<Pair<Receiver, AnnotationMirror>> superPost2 = resolveContracts(superPost, overridden);
            Set<Pair<Receiver, AnnotationMirror>> subPost2 = resolveContracts(subPost, overrider);
            @SuppressWarnings("CompilerMessages")
            /*@CompilerMessageKey*/ String postmsg = "contracts.postcondition." + msgKey + ".invalid";
            checkContractsSubset(overriderMeth, overriderTyp, overriddenMeth, overriddenTyp, superPost2,
                    subPost2, postmsg);

            // Check preconditions
            Set<ContractsUtils.PreOrPostcondition> superPre = contracts
                    .getPreconditions(overridden.getElement());
            Set<ContractsUtils.PreOrPostcondition> subPre = contracts.getPreconditions(overrider
                    .getElement());
            Set<Pair<Receiver, AnnotationMirror>> superPre2 = resolveContracts(superPre, overridden);
            Set<Pair<Receiver, AnnotationMirror>> subPre2 = resolveContracts(subPre, overrider);
            @SuppressWarnings("CompilerMessages")
            /*@CompilerMessageKey*/ String premsg = "contracts.precondition." + msgKey + ".invalid";
            checkContractsSubset(overriderMeth, overriderTyp, overriddenMeth, overriddenTyp, subPre2, superPre2,
                    premsg);

            // Check conditional postconditions
            Set<ContractsUtils.ConditionalPostcondition> superCPost = contracts
                    .getConditionalPostconditions(overridden.getElement());
            Set<ContractsUtils.ConditionalPostcondition> subCPost = contracts
                    .getConditionalPostconditions(overrider.getElement());
            // consider only 'true' postconditions
            Set<ContractsUtils.PreOrPostcondition> superCPostTrue = filterConditionalPostconditions(
                    superCPost, true);
            Set<ContractsUtils.PreOrPostcondition> subCPostTrue = filterConditionalPostconditions(
                    subCPost, true);
            Set<Pair<Receiver, AnnotationMirror>> superCPostTrue2 = resolveContracts(
                    superCPostTrue, overridden);
            Set<Pair<Receiver, AnnotationMirror>> subCPostTrue2 = resolveContracts(
                    subCPostTrue, overrider);
            @SuppressWarnings("CompilerMessages")
            /*@CompilerMessageKey*/ String posttruemsg = "contracts.conditional.postcondition.true." + msgKey + ".invalid";
            checkContractsSubset(overriderMeth, overriderTyp, overriddenMeth, overriddenTyp, superCPostTrue2, subCPostTrue2,
                    posttruemsg);

            // consider only 'false' postconditions
            Set<ContractsUtils.PreOrPostcondition> superCPostFalse = filterConditionalPostconditions(
                    superCPost, false);
            Set<ContractsUtils.PreOrPostcondition> subCPostFalse = filterConditionalPostconditions(
                    subCPost, false);
            Set<Pair<Receiver, AnnotationMirror>> superCPostFalse2 = resolveContracts(
                    superCPostFalse, overridden);
            Set<Pair<Receiver, AnnotationMirror>> subCPostFalse2 = resolveContracts(
                    subCPostFalse, overrider);
            @SuppressWarnings("CompilerMessages")
            /*@CompilerMessageKey*/ String postfalsemsg = "contracts.conditional.postcondition.false." + msgKey + ".invalid";
            checkContractsSubset(overriderMeth, overriderTyp, overriddenMeth, overriddenTyp, superCPostFalse2, subCPostFalse2,
                    postfalsemsg);
        }

        private boolean checkMemberReferenceReceivers() {
            JCTree.JCMemberReference memberTree = (JCTree.JCMemberReference) overriderTree;

            if (overridingType.getKind() == TypeKind.ARRAY) {
                // Assume the receiver for all method on arrays are @Top
                // This simplifies some logic because an AnnotatedExecutableType for an array method
                // (ie String[]::clone) has a receiver of "Array." The UNBOUND check would then
                // have to compare "Array" to "String[]".
                return true;
            }

            // These act like a traditional override
            if (memberTree.kind == JCTree.JCMemberReference.ReferenceKind.UNBOUND) {
                AnnotatedTypeMirror overriderReceiver = overrider.getReceiverType();
                AnnotatedTypeMirror overriddenReceiver = overridden.getParameterTypes().get(0);
                boolean success = atypeFactory.getTypeHierarchy().isSubtype(overriddenReceiver, overriderReceiver);
                if (!success) {
                    checker.report(Result.failure("methodref.receiver.invalid",
                            overriderMeth, overriderTyp, overriddenMeth, overriddenTyp,
                            overriderReceiver,
                            overriddenReceiver),
                            overriderTree);
                }
                return success;
            }

            // The rest act like method invocations
            AnnotatedTypeMirror receiverDecl;
            AnnotatedTypeMirror receiverArg;
            switch (memberTree.kind) {
                case UNBOUND:
                    ErrorReporter.errorAbort("Case UNBOUND should already be handled.");
                    return true; // Dead code
                case SUPER:
                    receiverDecl = overrider.getReceiverType();
                    receiverArg = atypeFactory.getAnnotatedType(memberTree.getQualifierExpression());

                    final AnnotatedTypeMirror selfType = atypeFactory.getSelfType(memberTree);
                    receiverArg.replaceAnnotations(selfType.getAnnotations());
                    break;
                case BOUND:
                    receiverDecl = overrider.getReceiverType();
                    receiverArg = overridingType;
                    break;
                case IMPLICIT_INNER:
                    receiverDecl = overrider.getReceiverType();
                    receiverArg = atypeFactory.getSelfType(memberTree);
                    break;
                case TOPLEVEL:
                case STATIC:
                case ARRAY_CTOR:
                default:
                    // Intentional fallthrough
                    // These don't have receivers
                    return true;
            }

            boolean success = atypeFactory.getTypeHierarchy().isSubtype(receiverArg, receiverDecl);
            if (!success) {
                checker.report(Result.failure("methodref.receiver.bound.invalid",
                        receiverArg, overriderMeth, overriderTyp,
                        receiverArg,
                        receiverDecl),
                        overriderTree);
            }

            return success;
        }

        private boolean checkReceiverOverride() {
            /*
             ** Don't do anything here because in Glacier, the only way for the receiver's annotation to be invalid is if it is also an invalid type.
            */
            return true;

            /*
            // Check the receiver type.
            // isSubtype() requires its arguments to be actual subtypes with
            // respect to JLS, but overrider receiver is not a subtype of the
            // overridden receiver.  Hence copying the annotations.
            // TODO: this will need to be improved for generic receivers.
            AnnotatedTypeMirror overriddenReceiver =
                    overrider.getReceiverType().getErased().shallowCopy(false);
            overriddenReceiver.addAnnotations(overridden.getReceiverType().getAnnotations());
            if (!atypeFactory.getTypeHierarchy().isSubtype(overriddenReceiver,
                    overrider.getReceiverType().getErased())) {
                checker.report(Result.failure("override.receiver.invalid",
                        overriderMeth, overriderTyp, overriddenMeth, overriddenTyp,
                        overrider.getReceiverType(),
                        overridden.getReceiverType()),
                        overriderTree);
                return false;
            }
            return true;
            */
        }

        private boolean checkParameters() {
            boolean result = true;
            // Check parameter values. (TODO: FIXME varargs)
            List<AnnotatedTypeMirror> overriderParams =
                    overrider.getParameterTypes();
            List<AnnotatedTypeMirror> overriddenParams =
                    overridden.getParameterTypes();

            // The functional interface of an unbound member reference has an extra parameter (the receiver).
            if (methodReference && ((JCTree.JCMemberReference)overriderTree).hasKind(JCTree.JCMemberReference.ReferenceKind.UNBOUND)) {
                overriddenParams = new ArrayList<>(overriddenParams);
                overriddenParams.remove(0);
            }
            for (int i = 0; i < overriderParams.size(); ++i) {
                boolean success = atypeFactory.getTypeHierarchy().isSubtype(overriddenParams.get(i), overriderParams.get(i));
                if (!success) {
                    success = testTypevarContainment(overriddenParams.get(i), overriderParams.get(i));
                }

                checkParametersMsg(success, i, overriderParams, overriddenParams);
                result &= success;
            }
            return result;
        }

        private void checkParametersMsg(boolean success, int index, List<AnnotatedTypeMirror> overriderParams, List<AnnotatedTypeMirror> overriddenParams) {
            String msgKey = methodReference ?  "methodref.param.invalid" : "override.param.invalid";
            long valuePos = overriderTree instanceof MethodTree ? positions.getStartPosition(root, ((MethodTree)overriderTree).getParameters().get(index))
                    : positions.getStartPosition(root, overriderTree);
            Tree posTree = overriderTree instanceof MethodTree ? ((MethodTree)overriderTree).getParameters().get(index) : overriderTree;

            if (checker.hasOption("showchecks")) {
                System.out.printf(
                        " %s (line %3d):%n     overrider: %s %s (parameter %d type %s)%n   overridden: %s %s (parameter %d type %s)%n",
                        (success ? "success: overridden parameter type is subtype of overriding" : "FAILURE: overridden parameter type is not subtype of overriding"),
                        (root.getLineMap() != null ? root.getLineMap().getLineNumber(valuePos) : -1),
                        overriderMeth, overriderTyp, index, overriderParams.get(index).toString(),
                        overriddenMeth, overriddenTyp, index, overriddenParams.get(index).toString());
            }
            if (!success) {
                checker.report(Result.failure(msgKey,
                        overriderMeth, overriderTyp,
                        overriddenMeth, overriddenTyp,
                        overriderParams.get(index).toString(),
                        overriddenParams.get(index).toString()),
                        posTree);
            }
        }

        private boolean checkReturn() {
            boolean success = true;
            // Check the return value.
            if ((overridingReturnType.getKind() != TypeKind.VOID)) {
                final TypeHierarchy typeHierarchy = atypeFactory.getTypeHierarchy();
                success = typeHierarchy.isSubtype(overridingReturnType, overriddenReturnType);

                // If both the overridden method have type variables as return types and both types were
                // defined in their respective methods then, they can be covariant or invariant
                // use super/subtypes for the overrides locations
                if (!success) {
                    success = testTypevarContainment(overridingReturnType, overriddenReturnType);

                    // sometimes when using a Java 8 compiler (not JSR308) the overridden return type of a method reference
                    // becomes a captured type.  This leads to defaulting that often makes the overriding return type
                    // invalid.  We ignore these.  This happens in Issue403/Issue404 when running without JSR308 Langtools
                    if (!success && methodReference) {

                        boolean isCaptureConverted =
                                (overriddenReturnType.getKind() == TypeKind.TYPEVAR) &&
                                        InternalUtils.isCaptured((TypeVariable) overriddenReturnType.getUnderlyingType());

                        if (methodReference && isCaptureConverted) {
                            ExecutableElement overridenMethod = overridden.getElement();
                            boolean isFunctionApply =
                                    overridenMethod.getSimpleName().toString().equals("apply") &&
                                            overridenMethod.getEnclosingElement().toString().equals("java.util.function.Function");

                            if (isFunctionApply) {
                                AnnotatedTypeMirror overridingUpperBound = ((AnnotatedTypeVariable) overriddenReturnType).getUpperBound();
                                success = typeHierarchy.isSubtype(overridingReturnType, overridingUpperBound);
                            }
                        }
                    }
                }

                checkReturnMsg(success);
            }
            return success;
        }

        private void checkReturnMsg(boolean success) {
            String msgKey = methodReference ?  "methodref.return.invalid" : "override.return.invalid";
            long valuePos = overriderTree instanceof MethodTree ? positions.getStartPosition(root, ((MethodTree)overriderTree).getReturnType())
                    : positions.getStartPosition(root, overriderTree);
            Tree posTree = overriderTree instanceof MethodTree ? ((MethodTree)overriderTree).getReturnType() : overriderTree;
            // The return type of a MethodTree is null for a constructor.
            if (posTree == null) {
                posTree = overriderTree;
            }

            if (checker.hasOption("showchecks")) {
                System.out.printf(
                        " %s (line %3d):%n     overrider: %s %s (return type %s)%n   overridden: %s %s (return type %s)%n",
                        (success ? "success: overriding return type is subtype of overridden" : "FAILURE: overriding return type is not subtype of overridden"),
                        (root.getLineMap() != null ? root.getLineMap().getLineNumber(valuePos) : -1),
                        overriderMeth, overriderTyp, overrider.getReturnType().toString(),
                        overriddenMeth, overriddenTyp, overridden.getReturnType().toString());
            }
            if (!success) {
                checker.report(Result.failure(msgKey,
                        overriderMeth, overriderTyp,
                        overriddenMeth, overriddenTyp,
                        overridingReturnType,
                        overriddenReturnType),
                        posTree);
            }
        }
    }

    /**
     * MJC: Copied private method. Tragic hack.
     *
     * Filters the set of conditional postconditions to return only those whose
     * annotation result value matches the value of the given boolean {@code b}.
     * For example, if {@code b == true}, then the following {@code @EnsuresNonNullIf}
     * conditional postcondition would match:<br>
     * {@code @EnsuresNonNullIf(expression="#1", result=true)}<br>
     * {@code boolean equals(@Nullable Object o)}
     */
    private Set<ContractsUtils.PreOrPostcondition> filterConditionalPostconditions(
            Set<ContractsUtils.ConditionalPostcondition> conditionalPostconditions, boolean b) {
        Set<ContractsUtils.PreOrPostcondition> result = new LinkedHashSet<ContractsUtils.PreOrPostcondition>();
        for (ContractsUtils.ConditionalPostcondition p : conditionalPostconditions) {
            if (p.annoResult == b) {
                result.add(new ContractsUtils.PreOrPostcondition(p.expression, p.annotationString));
            }
        }
        return result;
    }

    /**
     * MJC: Copied private method. Tragic hack.
     * Checks that {@code mustSubset} is a subset of {@code set} in the
     * following sense: For every expression in {@code mustSubset} there must be the
     * same expression in {@code set}, with the same (or a stronger) annotation.
     */
    private void checkContractsSubset(
            String overriderMeth, String overriderTyp, String overriddenMeth, String overriddenTyp,
            Set<Pair<Receiver, AnnotationMirror>> mustSubset,
            Set<Pair<Receiver, AnnotationMirror>> set, /*@CompilerMessageKey*/ String messageKey) {
        for (Pair<Receiver, AnnotationMirror> a : mustSubset) {
            boolean found = false;

            for (Pair<Receiver, AnnotationMirror> b : set) {
                // are we looking at a contract of the same receiver?
                if (a.first.equals(b.first)) {
                    // check subtyping relationship of annotations
                    QualifierHierarchy qualifierHierarchy = atypeFactory.getQualifierHierarchy();
                    if (qualifierHierarchy.isSubtype(a.second, b.second)) {
                        found = true;
                        break;
                    }
                }
            }

            if (!found) {
                MethodTree method = visitorState.getMethodTree();
                checker.report(Result.failure(messageKey,
                        overriderMeth, overriderTyp, overriddenMeth, overriddenTyp,
                        a.second, a.first), method);
            }
        }
    }

    /**
     * MJC: Copied private method. Tragic hack.
     *
     * Takes a set of contracts identified by their expression and annotation
     * strings and resolves them to the correct {@link Receiver} and
     * {@link AnnotationMirror}.
     */
    private Set<Pair<Receiver, AnnotationMirror>> resolveContracts(
            Set<ContractsUtils.PreOrPostcondition> contractSet, AnnotatedExecutableType method) {
        Set<Pair<Receiver, AnnotationMirror>> result = new HashSet<>();
        MethodTree methodTree = visitorState.getMethodTree();
        TreePath path = atypeFactory.getPath(methodTree);
        FlowExpressionContext flowExprContext = null;
        for (ContractsUtils.PreOrPostcondition p : contractSet) {
            String expression = p.expression;
            AnnotationMirror annotation = AnnotationUtils.fromName(
                    atypeFactory.getElementUtils(), p.annotationString);

            // Only check if the postcondition concerns this checker
            if (!atypeFactory.isSupportedQualifier(annotation)) {
                continue;
            }
            if (flowExprContext == null) {
                flowExprContext = FlowExpressionContext
                        .buildContextForMethodDeclaration(methodTree, method
                                        .getReceiverType().getUnderlyingType(),
                                checker.getContext());
            }

            try {
                // TODO: currently, these expressions are parsed many times.
                // this could
                // be optimized to store the result the first time.
                // (same for other annotations)
                FlowExpressions.Receiver expr = FlowExpressionParseUtil.parse(
                        expression, flowExprContext, path, false);
                result.add(Pair.of(expr, annotation));
            } catch (FlowExpressionParseException e) {
                // errors are reported elsewhere + ignore this contract
            }
        }
        return result;
    }

}
