/*
   Copyright 2018 Booz Allen Hamilton

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package org.boozallen.plugins.jte.config

import org.apache.commons.lang.StringEscapeUtils
import org.codehaus.groovy.control.CompilerConfiguration
import org.kohsuke.groovy.sandbox.SandboxTransformer
import groovy.json.JsonSlurper
import groovy.json.JsonOutput

/*
  Parses an Template Config File and returns a TemplateConfigObject

  example usage:

    TemplateConfigObject my_config = TemplateConfigDsl.parse("""
      libraries{
        owasp_zap{
          target = "example.com"
        }
      }
    """)
*/
class TemplateConfigDsl implements Serializable{
  
  static TemplateConfigObject parse(String script_text){

    TemplateConfigObject templateConfig = new TemplateConfigObject()
    
    Binding our_binding = new Binding(templateConfig: templateConfig)
    
    CompilerConfiguration cc = new CompilerConfiguration()
    cc.addCompilationCustomizers(new SandboxTransformer())
    cc.scriptBaseClass = TemplateConfigBuilder.class.name
    
    GroovyShell sh = new GroovyShell(TemplateConfigDsl.classLoader, our_binding, cc);

    script_text = script_text.replaceAll("@merge", "builderMerge();")
    script_text = script_text.replaceAll("@override", "builderOverride();")
    
    TemplateConfigDslSandbox sandbox = new TemplateConfigDslSandbox()
    sandbox.register();
    try {
      sh.evaluate script_text
    }finally {
      sandbox.unregister();
    }
    
    return templateConfig
  }

  static String serialize(TemplateConfigObject configObj){
    Map config = new JsonSlurper().parseText(JsonOutput.toJson(configObj.getConfig()))

    def depth = 0
    ArrayList file = [] 
    ArrayList keys = []
    return printBlock(file, depth, config, keys, configObj).join("\n")
  }

  static ArrayList printBlock(ArrayList file, depth, Map block, ArrayList keys, TemplateConfigObject configObj){
    String tab = "    "
    block.each{ key, value -> 
      String coordinate = keys.size() ? "${keys.join(".")}.${key}" : key 
      String merge = (coordinate in configObj.merge) ? "@merge " : "" 
      String override = (coordinate in configObj.override) ? "@override " : "" 
      if(value instanceof Map){
        String nodeName = key.contains("-") ? "'${key}'" : key
        if (value == [:]){
          file += "${tab*depth}${merge}${override}${nodeName}{}"
        }else{
          file += "${tab*depth}${merge}${override}${nodeName}{"
          depth++
          keys.push(key)
          file = printBlock(file, depth, value, keys, configObj)
          keys.pop()
          depth-- 
          file += "${tab*depth}}"
        }
      }else{
        if (value instanceof String){
          file += "${tab*depth}${merge}${override}${key} = '${StringEscapeUtils.escapeJava(value)}'"
        } else if (value instanceof ArrayList){
          file += "${tab*depth}${merge}${override}${key} = ${value.inspect()}" 
        }else{
          file += "${tab*depth}${merge}${override}${key} = ${value}" 
        }
      }
    }
    return file 
  }

}
