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

package org.boozallen.plugins.jte.hooks

import hudson.model.Result

/*
    A HookContext object stores runtime context for the event
    that triggered the hook
*/
class HookContext implements Serializable {

    String library
    String step 
    Result status 
    def args 

    HookContext(String library, String step, def args, Result status){
        this.library = library 
        this.step = step 
        this.args = args 
        this.status = status 
    }

    HookContext(Result status){
        this.status = status 
        this.library = null
        this.step = null 
        this.args = []; 
    }

}

