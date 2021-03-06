package com.fasterxml.jackson.databind.deser.std;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;
import com.fasterxml.jackson.databind.jsontype.TypeDeserializer;
import com.fasterxml.jackson.databind.type.ReferenceType;

/**
 * Base deserializer implementation for properties {@link ReferenceType} values.
 * Implements most of functionality, only leaving couple of abstract
 * methods for sub-classes to implement
 *
 * @since 2.8
 */
public abstract class ReferenceTypeDeserializer<T>
    extends StdDeserializer<T>
    implements ContextualDeserializer
{
    private static final long serialVersionUID = 2L; // 2.9

    /**
     * Full type of property (or root value) for which this deserializer
     * has been constructed and contextualized.
     */
    protected final JavaType _fullType;

    protected final ValueInstantiator _valueInstantiator;

    protected final TypeDeserializer _valueTypeDeserializer;
    protected final JsonDeserializer<Object> _valueDeserializer;

    /*
    /**********************************************************
    /* Life-cycle
    /**********************************************************
     */

    @SuppressWarnings("unchecked")
    public ReferenceTypeDeserializer(JavaType fullType, ValueInstantiator vi,
            TypeDeserializer typeDeser, JsonDeserializer<?> deser)
    {
        super(fullType);
        _valueInstantiator = vi;
        _fullType = fullType;
        _valueDeserializer = (JsonDeserializer<Object>) deser;
        _valueTypeDeserializer = typeDeser;
    }

    @Deprecated // since 2.9
    public ReferenceTypeDeserializer(JavaType fullType,
            TypeDeserializer typeDeser, JsonDeserializer<?> deser)
    {
        this(fullType, null, typeDeser, deser);
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) throws JsonMappingException
    {
        JsonDeserializer<?> deser = _valueDeserializer;
        if (deser == null) {
            deser = ctxt.findContextualValueDeserializer(_fullType.getReferencedType(), property);
        } else { // otherwise directly assigned, probably not contextual yet:
            deser = ctxt.handleSecondaryContextualization(deser, property, _fullType.getReferencedType());            
        }
        TypeDeserializer typeDeser = _valueTypeDeserializer;
        if (typeDeser != null) {
            typeDeser = typeDeser.forProperty(property);
        }
        if ((deser == _valueDeserializer) && (typeDeser == _valueTypeDeserializer)) {
            return this;
        }

        // !!! 23-Oct-2016, tatu: TODO: full support for configurable ValueInstantiators?
        return withResolved(typeDeser, deser);
    }

    /*
    /**********************************************************
    /* Abstract methods for sub-classes to implement
    /**********************************************************
     */

    protected abstract ReferenceTypeDeserializer<T> withResolved(TypeDeserializer typeDeser, JsonDeserializer<?> valueDeser);

    @Override
    public abstract T getNullValue(DeserializationContext ctxt);

    public abstract T referenceValue(Object contents);

    /**
     * Method called in case of "merging update", in which we should try
     * update reference instead of creating a new one. If this does not
     * succeed, should just create a new instance.
     *
     * @since 2.9
     */
    public abstract T updateReference(T reference, Object contents);

    /**
     * Method that may be called to find contents of specified reference,
     * if any; or `null` if none. Note that method should never fail, so
     * for types that use concept of "absence" vs "presence", `null` is
     * to be returned for both "absent" and "reference to `null`" cases.
     *
     * @since 2.9
     */
    public abstract Object getReferenced(T reference);
    
    /*
    /**********************************************************
    /* Overridden accessors
    /**********************************************************
     */

    @Override
    public JavaType getValueType() { return _fullType; }

    /**
     * By default we assume that updateability mostly relies on value
     * deserializer; if it supports updates, typically that's what
     * matters. So let's just delegate.
     */
    @Override // since 2.9
    public Boolean supportsUpdate(DeserializationConfig config) {
        return _valueDeserializer.supportsUpdate(config);
    }

    /*
    /**********************************************************
    /* Deserialization
    /**********************************************************
     */

    @Override
    public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        // 23-Oct-2016, tatu: ValueInstantiator only defined for non-vanilla instances,
        //    but do check... might work
        if (_valueInstantiator != null) {
            @SuppressWarnings("unchecked")
            T value = (T) _valueInstantiator.createUsingDefault(ctxt);
            return deserialize(p, ctxt, value);
        }
        Object contents = (_valueTypeDeserializer == null)
                ? _valueDeserializer.deserialize(p, ctxt)
                : _valueDeserializer.deserializeWithType(p, ctxt, _valueTypeDeserializer);
        return referenceValue(contents);
    }

    @Override
    public T deserialize(JsonParser p, DeserializationContext ctxt, T reference) throws IOException {
        Object contents = getReferenced(reference);
        // Whether to error or not... for now, just go back to default then
        if ((contents == null) || (_valueTypeDeserializer != null)) {
            contents = (_valueTypeDeserializer == null)
                    ? _valueDeserializer.deserialize(p, ctxt)
                    : _valueDeserializer.deserializeWithType(p, ctxt, _valueTypeDeserializer);
            return referenceValue(contents);
        }
        contents = _valueDeserializer.deserialize(p, ctxt, contents);
        return updateReference(reference, contents);
    }

    @Override
    public Object deserializeWithType(JsonParser p, DeserializationContext ctxt,
            TypeDeserializer typeDeserializer) throws IOException
    {
        final JsonToken t = p.getCurrentToken();
        if (t == JsonToken.VALUE_NULL) { // can this actually happen?
            return getNullValue(ctxt);
        }
        // 22-Oct-2015, tatu: This handling is probably not needed (or is wrong), but
        //   could be result of older (pre-2.7) Jackson trying to serialize natural types.
        //  Because of this, let's allow for now, unless proven problematic
        /*
        if ((t != null) && t.isScalarValue()) {
            return deserialize(p, ctxt);
        }
        */
        // 19-Apr-2016, tatu: Alas, due to there typically really being anything for Reference wrapper
        //   itself, need to just ignore `typeDeser`, use TypeDeserializer we do have for contents
        //   and it might just work.

        if (_valueTypeDeserializer == null) {
            return deserialize(p, ctxt);
        }
        return referenceValue(_valueTypeDeserializer.deserializeTypedFromAny(p, ctxt));
    }
}
