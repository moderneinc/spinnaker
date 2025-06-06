/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.spinnaker.gate.controllers;

import com.netflix.spinnaker.gate.services.ServerGroupManagerService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/applications/{application}/serverGroupManagers")
public class ServerGroupManagerController {
  private final ServerGroupManagerService serverGroupManagerService;

  @Autowired
  ServerGroupManagerController(ServerGroupManagerService serverGroupManagerService) {
    this.serverGroupManagerService = serverGroupManagerService;
  }

  @Operation(summary = "Retrieve a list of server group managers for an application")
  @RequestMapping(method = RequestMethod.GET)
  public List<Map> getServerGroupManagersForApplication(@PathVariable String application) {
    return this.serverGroupManagerService.getServerGroupManagersForApplication(application);
  }
}
