aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 339267708833.dkr.ecr.us-east-1.amazonaws.com
docker build -f Dockerfile -t 339267708833.dkr.ecr.us-east-1.amazonaws.com/prebid:v1.1.0 .
docker push 339267708833.dkr.ecr.us-east-1.amazonaws.com/prebid:v1.1.0
