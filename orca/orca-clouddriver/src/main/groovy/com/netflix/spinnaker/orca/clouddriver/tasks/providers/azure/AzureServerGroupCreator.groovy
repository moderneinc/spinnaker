/*
 * Copyright 2014 Netflix, Inc.
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

package com.netflix.spinnaker.orca.clouddriver.tasks.providers.azure

import com.netflix.spinnaker.orca.api.pipeline.models.StageExecution
import com.netflix.spinnaker.orca.clouddriver.tasks.servergroup.ServerGroupCreator
import com.netflix.spinnaker.orca.kato.tasks.DeploymentDetailsAware
import groovy.util.logging.Slf4j
import org.springframework.stereotype.Component

@Slf4j
@Component
class AzureServerGroupCreator implements ServerGroupCreator, DeploymentDetailsAware {

  boolean katoResultExpected = false
  String cloudProvider = "azure"

  @Override
  List<Map> getOperations(StageExecution stage) {
    def operation = [:]

    operation.putAll(stage.context)

    if (operation.account && !operation.credentials) {
      operation.credentials = operation.account
    }

    if (operation.image?.imageName || operation.image?.uri) {
      // Image already populated, nothing to do
      log.info("Using existing image configuration: ${operation.image.imageName}")
    } else {
      // Get the previous stage with image using existing method
      def imageStage = getPreviousStageWithImage(stage, operation.region ?: stage.context.region, cloudProvider)

      if (imageStage) {
        // Check the type of stage and handle accordingly
        if (imageStage.type == "findImageFromTags" && imageStage.context?.amiDetails) {
          // Use the image from Find Image from Tags stage
          def imageDetails = imageStage.context.amiDetails[0]
          operation.image = operation.image ?: [:]
          operation.image.isCustom = true
          operation.image.uri = imageDetails.imageId
          operation.image.imageName = imageDetails.imageName
          operation.image.region = imageDetails.region ?: operation.region
          operation.image.ostype = imageDetails.osType ?: "linux"
          // Clear marketplace fields for custom images
          operation.image.publisher = ""
          operation.image.offer = ""
          operation.image.sku = ""
          operation.image.version = ""
          log.info("Using image from Find Image from Tags stage: ${operation.image.imageName}")
        } else if (imageStage.type == "bake") {
          // Use the image from bake stage
          operation.image = operation.image ?: [:]
          operation.image.isCustom = true
          operation.image.uri = imageStage.context?.imageId
          operation.image.ostype = imageStage.context?.osType
          operation.image.imageName = imageStage.context?.imageName
          log.info("Using image from bake stage: ${operation.image.imageName}")
        }
      }
    }

    return [[(ServerGroupCreator.OPERATION): operation]]
  }

  @Override
  Optional<String> getHealthProviderName() {
    return Optional.empty()
  }
}
