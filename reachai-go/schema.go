package reachai

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"reflect"
	"sort"
	"strings"
)

// The deliberately small JSON Schema subset is validated rather than silently ignored.
type schema struct {
	kind       string
	nullable   bool
	properties map[string]*schema
	required   map[string]bool
	items      *schema
	enum       []any
	wire       map[string]any
}

func decodeJSON(data []byte, out any) error {
	d := json.NewDecoder(bytes.NewReader(data))
	d.UseNumber()
	if err := d.Decode(out); err != nil {
		return err
	}
	var extra any
	if err := d.Decode(&extra); err != io.EOF {
		return errors.New("trailing JSON content")
	}
	return nil
}
func copyJSON(value any) (any, error) {
	b, err := json.Marshal(value)
	if err != nil {
		return nil, err
	}
	var out any
	err = decodeJSON(b, &out)
	return out, err
}
func parseSchema(input map[string]any) (*schema, error) {
	v, err := copyJSON(input)
	if err != nil {
		return nil, err
	}
	m, ok := v.(map[string]any)
	if !ok {
		return nil, errors.New("schema must be object")
	}
	return parseSchemaAt(m, 0)
}
func parseSchemaAt(m map[string]any, depth int) (*schema, error) {
	if depth > 20 {
		return nil, errors.New("schema nesting too deep")
	}
	for k := range m {
		switch k {
		case "type", "properties", "required", "items", "additionalProperties", "description", "title", "enum":
		default:
			return nil, fmt.Errorf("unsupported schema keyword %s", k)
		}
	}
	s := &schema{properties: map[string]*schema{}, required: map[string]bool{}, wire: m}
	switch v := m["type"].(type) {
	case string:
		s.kind = v
	case []any:
		if len(v) != 2 {
			return nil, errors.New("invalid nullable schema")
		}
		for _, x := range v {
			str, ok := x.(string)
			if !ok {
				return nil, errors.New("invalid nullable schema")
			}
			if str == "null" {
				s.nullable = true
			} else if s.kind == "" {
				s.kind = str
			} else {
				return nil, errors.New("invalid nullable schema")
			}
		}
		if !s.nullable {
			return nil, errors.New("invalid union")
		}
	default:
		return nil, errors.New("schema type required")
	}
	switch s.kind {
	case "string", "integer", "number", "boolean", "array", "object", "null":
	default:
		return nil, errors.New("unsupported schema type")
	}
	if s.kind != "object" {
		for _, key := range []string{"properties", "required", "additionalProperties"} {
			if _, exists := m[key]; exists {
				return nil, errors.New("object keyword on non-object schema")
			}
		}
	}
	if s.kind != "array" {
		if _, exists := m["items"]; exists {
			return nil, errors.New("items on non-array schema")
		}
	}
	if raw, ok := m["enum"]; ok {
		v, ok := raw.([]any)
		if !ok || len(v) == 0 {
			return nil, errors.New("invalid enum")
		}
		s.enum = v
	}
	if s.kind == "array" {
		item, ok := m["items"].(map[string]any)
		if !ok {
			return nil, errors.New("array items required")
		}
		var err error
		s.items, err = parseSchemaAt(item, depth+1)
		if err != nil {
			return nil, err
		}
	}
	if s.kind == "object" {
		props, ok := m["properties"].(map[string]any)
		if !ok {
			return nil, errors.New("object properties required")
		}
		if v, exists := m["additionalProperties"]; exists && v != false {
			return nil, errors.New("dynamic object properties unsupported")
		}
		m["additionalProperties"] = false
		for name, raw := range props {
			if !capName.MatchString(name) {
				return nil, errors.New("invalid property name")
			}
			child, ok := raw.(map[string]any)
			if !ok {
				return nil, errors.New("invalid property schema")
			}
			var err error
			s.properties[name], err = parseSchemaAt(child, depth+1)
			if err != nil {
				return nil, err
			}
		}
		if raw, exists := m["required"]; exists {
			items, ok := raw.([]any)
			if !ok {
				return nil, errors.New("invalid required list")
			}
			for _, v := range items {
				name, ok := v.(string)
				if !ok || s.properties[name] == nil || s.required[name] {
					return nil, errors.New("invalid required field")
				}
				s.required[name] = true
			}
		}
	}
	if len(s.enum) > 0 {
		values := s.enum
		s.enum = nil
		for _, value := range values {
			if err := s.validate(value); err != nil {
				return nil, errors.New("enum violates schema type")
			}
		}
		s.enum = values
	}
	return s, nil
}
func (s *schema) validate(value any) error {
	if value == nil {
		if !s.nullable && s.kind != "null" {
			return errors.New("null not permitted")
		}
		if len(s.enum) > 0 {
			for _, v := range s.enum {
				if v == nil {
					return nil
				}
			}
			return errors.New("value not in enum")
		}
		return nil
	}
	switch s.kind {
	case "string":
		if _, ok := value.(string); !ok {
			return errors.New("string required")
		}
	case "boolean":
		if _, ok := value.(bool); !ok {
			return errors.New("boolean required")
		}
	case "integer":
		v, ok := value.(json.Number)
		if !ok {
			return errors.New("integer required")
		}
		if _, err := v.Int64(); err != nil {
			f, e := v.Float64()
			if e != nil || math.IsNaN(f) || math.IsInf(f, 0) || math.Trunc(f) != f || f < -9223372036854775808.0 || f >= 9223372036854775808.0 {
				return errors.New("integer out of range")
			}
		}
	case "number":
		v, ok := value.(json.Number)
		if !ok {
			return errors.New("number required")
		}
		f, err := v.Float64()
		if err != nil || math.IsInf(f, 0) || math.IsNaN(f) {
			return errors.New("invalid number")
		}
	case "array":
		a, ok := value.([]any)
		if !ok {
			return errors.New("array required")
		}
		for _, x := range a {
			if err := s.items.validate(x); err != nil {
				return err
			}
		}
	case "object":
		m, ok := value.(map[string]any)
		if !ok {
			return errors.New("object required")
		}
		for key := range s.required {
			if _, ok := m[key]; !ok {
				return errors.New("required property missing")
			}
		}
		for key, v := range m {
			child := s.properties[key]
			if child == nil {
				return errors.New("unknown property")
			}
			if err := child.validate(v); err != nil {
				return err
			}
		}
	default:
		return errors.New("null required")
	}
	if len(s.enum) > 0 {
		found := false
		for _, v := range s.enum {
			if reflect.DeepEqual(v, value) {
				found = true
				break
			}
		}
		if !found {
			return errors.New("value not in enum")
		}
	}
	return nil
}
func (s *schema) metadata() map[string]any {
	m := map[string]any{"nullable": s.nullable}
	if len(s.enum) > 0 {
		m["enum"] = s.enum
	}
	if s.items != nil {
		m["itemsType"] = s.items.kind
		m["itemsMetadata"] = s.items.metadata()
	}
	return m
}
func (s *schema) children() []map[string]any {
	if s.kind == "array" {
		return s.items.children()
	}
	names := make([]string, 0, len(s.properties))
	for name := range s.properties {
		names = append(names, name)
	}
	sort.Strings(names)
	result := make([]map[string]any, 0, len(names))
	for _, name := range names {
		child := s.properties[name]
		description, _ := child.wire["description"].(string)
		result = append(result, map[string]any{"name": name, "type": child.kind, "required": s.required[name], "location": "BODY", "description": strings.TrimSpace(description), "children": child.children(), "metadata": child.metadata()})
	}
	return result
}
